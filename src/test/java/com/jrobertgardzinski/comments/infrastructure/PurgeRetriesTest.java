package com.jrobertgardzinski.comments.infrastructure;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.comments.application.MarkUserCommentsForErasure;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import com.jrobertgardzinski.comments.application.RestoreUserComments;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The error policy where it matters — driving the REAL error handler with the REAL listener, in the
 * loop the container would run: deliver, and on a failure ask the handler whether to redeliver.
 *
 * <p>The two outcomes that have to be different, and were not:
 * <ul>
 *   <li>a store that is broken for a moment (Postgres restarting, the Hikari pool momentarily empty):
 *       the command must SURVIVE it. Before this round ten attempts inside one millisecond were spent
 *       on it and the command was dropped with the offset committed — the purge simply never happened,
 *       and the saga discovered it 120 seconds later;</li>
 *   <li>a store that is broken for good: the retrying must END. The sibling participant on Helidon
 *       retries forever, which for a participant of THIS saga means purging the content of an account
 *       the orchestrator has already restored to its owner (it capitulates at ≈165s).</li>
 * </ul>
 *
 * <p>The budget under test is milliseconds rather than the production 90 seconds — a test nobody waits
 * out is a test nobody runs — and {@link SagaRetryBudgetTest} pins the real numbers on a steered clock.
 */
class PurgeRetriesTest {

    private static final String LEAVER = "leaver@example.com";
    private static final String SAGA = "3b8f0e64-2f1d-4b8b-9a44-6f9a2b1c0d55";
    private static final String COMMAND = "{\"type\":\"PURGE_USER_CONTENT\",\"email\":\"" + LEAVER
            + "\",\"sagaId\":\"" + SAGA + "\"}";

    private final PurgeUserComments purgeUserComments = mock(PurgeUserComments.class);
    private final PurgeConfirmations confirmations = mock(PurgeConfirmations.class);
    private final MarkUserCommentsForErasure markForErasure = mock(MarkUserCommentsForErasure.class);
    private final PurgeCommandsListener listener = new PurgeCommandsListener(markForErasure,
            mock(RestoreUserComments.class), purgeUserComments, confirmations, new ObjectMapper(),
            NoTransactions.template());

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DefaultErrorHandler errorHandler = SagaParticipantConfig.errorHandler(
            new SagaRetryBudget(Duration.ofMillis(400), Duration.ofMillis(20),
                    Duration.ofMillis(50), System::nanoTime),
            meters);

    private final MessageListenerContainer container = mock(MessageListenerContainer.class);
    private final Consumer<?, ?> consumer = mock(Consumer.class);

    private final ListAppender<ILoggingEvent> logLines = new ListAppender<>();

    @BeforeEach
    void tapTheLog() {
        // the container has to look alive: the handler's backoff sleeps only while it is running
        when(container.isRunning()).thenReturn(true);
        logLines.start();
        policyLogger().addAppender(logLines);
    }

    @AfterEach
    void untapTheLog() {
        policyLogger().detachAppender(logLines);
        logLines.stop();
    }

    private static Logger policyLogger() {
        return (Logger) LoggerFactory.getLogger(SagaParticipantConfig.class);
    }

    private ConsumerRecord<String, String> command() {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("content-commands", 0, 42L, LEAVER, COMMAND);
        // the cid the deletion request started with, as the orchestrator's producer stamps it
        record.headers().add(KafkaTracing.HEADER, "cid-of-the-deletion".getBytes(StandardCharsets.UTF_8));
        return record;
    }

    /**
     * One container cycle: hand the record to the listener and, if it throws, let the error handler
     * decide. Returns whether the record was DROPPED (recovered, offset committed) rather than
     * redelivered.
     */
    private boolean deliverOnce() throws Exception {
        try {
            listener.receive(COMMAND, "cid-of-the-deletion");
            return false;
        } catch (Exception failed) {
            return errorHandler.handleOne(failed, command(), consumer, container);
        }
    }

    @Test
    @DisplayName("a store outage that passes: the command is redelivered and the mark then happens")
    void a_transient_outage_is_ridden_out() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        Mockito.doAnswer(attempt -> {
            if (attempts.incrementAndGet() == 1) {
                throw new org.springframework.dao.QueryTimeoutException("the pool is momentarily empty");
            }
            return null;
        }).when(markForErasure).execute(LEAVER);

        assertFalse(deliverOnce(), "the first delivery fails — the handler must ask for a redelivery,"
                + " not commit the offset over a mark that did not happen");
        assertFalse(deliverOnce(), "and the second one succeeds, so nothing is recovered/dropped");

        assertEquals(2, attempts.get(), "the mark ran again on redelivery — it is idempotent by design");
        Mockito.verify(confirmations).confirm(SAGA, LEAVER);
        assertEquals(0, dropped(), "nothing was dropped, so the counter that alerts stays at zero");
    }

    @Test
    @DisplayName("a store outage that does not pass: the retrying ends with the budget, not never")
    void a_permanent_outage_ends_with_the_budget() throws Exception {
        Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("no database"))
                .when(markForErasure).execute(LEAVER);

        int deliveries = 0;
        long startedAt = System.nanoTime();
        while (!deliverOnce()) {
            deliveries++;
            assertTrue(deliveries < 500, "the retrying is not bounded by anything — this is the"
                    + " eternal-retry failure mode the budget exists to prevent");
        }
        Duration spent = Duration.ofNanos(System.nanoTime() - startedAt);

        assertTrue(deliveries >= 2, "a budget that gives up after one attempt is the old behaviour"
                + " with extra steps, was: " + deliveries);
        assertTrue(spent.compareTo(Duration.ofSeconds(30)) < 0,
                "and it ended on the budget's deadline, not on the loop's guard: " + spent);
        Mockito.verify(confirmations, Mockito.never()).confirm(SAGA, LEAVER);
    }

    @Test
    @DisplayName("the drop is counted and logged by coordinates — never by payload, which names the leaver")
    void the_drop_is_counted_and_says_nothing_private() throws Exception {
        Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException(
                        "FATAL: password authentication failed for user \"" + LEAVER + "\""))
                .when(markForErasure).execute(LEAVER);

        int deliveries = 0;
        while (!deliverOnce()) {
            // spend the budget — bounded, because a listener that stops throwing would otherwise
            // spin here for ever instead of failing (it did, on the day the mark was introduced)
            assertTrue(deliveries++ < 500, "the retrying is not bounded by anything");
        }

        assertEquals(1, dropped(), "one increment = one account deletion this service did not finish;"
                + " the old behaviour dropped commands with no counter at all");
        List<String> lines = logLines.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertTrue(lines.stream().anyMatch(line -> line.contains("DROPPED")
                        && line.contains("content-commands-0@42")),
                "the drop names the record's coordinates so an operator can find it: " + lines);
        assertFalse(lines.stream().anyMatch(line -> line.contains(LEAVER)),
                "and a driver's own message may quote row values — the address must not survive into"
                        + " the retry or drop lines: " + lines);
        assertTrue(lines.stream().anyMatch(line ->
                        line.contains("DataAccessResourceFailureException")),
                "the failure's TYPE is what an operator triages on: " + lines);
    }

    private double dropped() {
        return meters.find("comments.kafka.records.dropped").counter() == null ? 0
                : meters.find("comments.kafka.records.dropped").counter().count();
    }
}
