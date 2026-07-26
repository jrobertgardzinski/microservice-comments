package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.outbox.spring.SpringOutbox;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * What makes this service a well-behaved PARTICIPANT of the account-deletion saga, in one place: the
 * error policy of the listener that consumes purge commands, the health lamp for the loop that runs
 * it, and the outbox-backed confirmation it sends back.
 *
 * <p>The 26.07 audit's second theme was that the participant role is implemented four times with four
 * different sets of guarantees. Round 10 unified the OUTBOX (a kernel library, one implementation);
 * this class unifies the CONSUMING side of the two Spring participants with what the two Helidon ones
 * already had: a bounded retry with backoff instead of ten immediate attempts and a silent commit, and
 * a readiness signal for a loop that has stopped turning.
 *
 * <p>The error policy and the confirmation channel hang off {@code comments.kafka-enabled}, like the
 * listener and the outbox they serve — without a broker there is neither a record to retry nor an
 * outbox to write to. The health lamp does NOT, and that asymmetry is forced rather than chosen:
 * Spring Boot validates health-group membership at startup, so a {@code readiness} group naming a
 * contributor that only exists with a broker would refuse to boot every broker-less run, the whole
 * test suite included. The lamp is therefore always registered and knows which of the two situations
 * it is reporting on (see {@link SagaListenersHealth}).
 */
@Configuration
class SagaParticipantConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SagaParticipantConfig.class);

    /** The stall tolerance as the OPERATOR spells it, for the message they read when it is refused. */
    static final String STALL_ENV = "COMMENTS_LISTENER_STALL_SEC";

    /**
     * The tolerance may be RAISED, never lowered below the longest stretch in which a HEALTHY loop
     * legitimately completes no poll — because those retries sleep ON the consumer thread. The bound
     * is arithmetic, not taste: the last attempt of a record can START one whole
     * {@link SagaRetryBudget} (90s) after the first failure, which itself can follow a Hikari
     * connection timeout (30s), and that last attempt can block for another 30s before it throws.
     * 30 + 90 + 30 = 150s. A smaller tolerance would report a purge riding out a database restart as a
     * dead listener, and an operator who learns to ignore the lamp has no lamp.
     */
    static final Duration STALL_FLOOR = SagaRetryBudget.BUDGET.plusSeconds(60);

    /**
     * The single error handler Spring Boot wires into the listener container factory, so it governs
     * BOTH listeners of this service: the saga's purge commands and the cascade's MEME_DELETED hop.
     * The budget's reasoning is the saga's timeline (see {@link SagaRetryBudget}), and it applies to the
     * cascade as a ceiling rather than as a deadline — nobody waits for a thread drop, so a hop that
     * keeps failing has no clock to beat, only a partition it must not block forever. The same numbers
     * serve both: a hop retried for 90 seconds and then dropped is strictly better off than one dropped
     * after ten attempts inside a millisecond, which is what happened before.
     */
    @Bean
    @ConditionalOnProperty(name = "comments.kafka-enabled", havingValue = "true")
    CommonErrorHandler sagaRecordErrorHandler(MeterRegistry meters) {
        return errorHandler(SagaRetryBudget.forSagaRecords(), meters);
    }

    /**
     * The handler as a plain factory as well as a {@code @Bean} body — so a test can drive the real
     * policy with a budget measured in milliseconds instead of sitting out 90 real seconds.
     *
     * <p>Two settings that are not defaults and matter:
     * <ul>
     *   <li>{@code resetStateOnExceptionChange(false)}: Spring Kafka's default RESTARTS the backoff
     *       whenever the exception type changes, which would hand a record an unbounded budget as soon
     *       as its failures alternate (a connection timeout, then a rollback, then a timeout again).
     *       The budget belongs to the saga's clock, not to the exception's identity.</li>
     *   <li>a {@link RetryListener}, so each attempt leaves a line naming the record's coordinates —
     *       without it the retrying is invisible until the drop.</li>
     * </ul>
     * Non-retryable failures keep Spring Kafka's own classification: a deserialization or conversion
     * error goes straight to the recoverer, because no amount of waiting fixes bytes that are not the
     * shape the listener parses. (This service's listener already drops malformed JSON itself, with a
     * PII-free WARN, before any of this is reached.)
     */
    static DefaultErrorHandler errorHandler(SagaRetryBudget budget, MeterRegistry meters) {
        DefaultErrorHandler handler = new DefaultErrorHandler(droppedAfterBudget(meters), budget);
        handler.setResetStateOnExceptionChange(false);
        handler.setRetryListeners(retryLogging());
        return handler;
    }

    /**
     * What happens when the budget is spent: the record is dropped and its offset committed — loudly,
     * and counted. The counter is the point. The old behaviour dropped commands too, only in
     * milliseconds and with nothing but a framework ERROR line, which is why the audit found the
     * contract broken ("the saga does not lose a purge") and no metric to prove it ever was.
     *
     * <p>{@code comments_kafka_records_dropped_total{topic="content-commands"}} is the alert an operator
     * wants: one increment means one account deletion that this service did not finish, and the saga
     * is about to compensate.
     */
    private static ConsumerRecordRecoverer droppedAfterBudget(MeterRegistry meters) {
        return (record, failure) -> {
            meters.counter("comments.kafka.records.dropped", "topic", record.topic()).increment();
            withCidOf(record, () -> LOG.error("giving up on {}-{}@{} after the {}s retry budget:"
                            + " the record is DROPPED and its offset committed ({}). If this was a"
                            + " purge command, the saga will time out and compensate",
                    record.topic(), record.partition(), record.offset(),
                    SagaRetryBudget.BUDGET.toSeconds(), typeChain(failure)));
            LOG.debug("the failure that exhausted the budget for {}-{}@{}", record.topic(),
                    record.partition(), record.offset(), failure);
        };
    }

    private static RetryListener retryLogging() {
        return new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> record, Exception failure, int attempt) {
                withCidOf(record, () -> LOG.warn("{}-{}@{} failed on attempt {} ({}) — retrying"
                                + " with backoff inside the {}s budget",
                        record.topic(), record.partition(), record.offset(), attempt,
                        typeChain(failure), SagaRetryBudget.BUDGET.toSeconds()));
                LOG.debug("attempt {} on {}-{}@{}", attempt, record.topic(), record.partition(),
                        record.offset(), failure);
            }

            @Override
            public void recovered(ConsumerRecord<?, ?> record, Exception failure) {
                // the drop is announced by the recoverer above, with the counter — one line, not two
            }
        };
    }

    /**
     * The record's correlation id back into the MDC for one log line. The listener's own {@code cid}
     * is long gone by the time an error handler runs (it is removed when the listener returns or
     * throws), so without this the retry and drop lines — the two lines an incident is reconstructed
     * from — would be the only ones in the trail without the id that joins them to the deletion
     * request.
     */
    private static void withCidOf(ConsumerRecord<?, ?> record, Runnable log) {
        Header cid = record.headers().lastHeader(KafkaTracing.HEADER);
        if (cid == null || cid.value() == null) {
            log.run();
            return;
        }
        MDC.put(CorrelationIdFilter.MDC_KEY, new String(cid.value(), StandardCharsets.UTF_8));
        try {
            log.run();
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    /**
     * The failure as its chain of TYPES, never its messages. A database driver puts row values into
     * some of its messages (PostgreSQL's {@code Detail: Key (author)=(…)}), and on this listener a row
     * value is the leaver's e-mail address — the exact leak the audit found in this service's log
     * lines. The type chain is what an operator actually triages on ("connection refused" versus
     * "rolled back" is a class, not a sentence), the record's coordinates say WHICH record, and the
     * throwable itself is one DEBUG line away for whoever needs it.
     */
    private static String typeChain(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        Throwable current = failure;
        while (current != null && chain.length() < 200) {
            if (!chain.isEmpty()) {
                chain.append(" <- ");
            }
            chain.append(current.getClass().getSimpleName());
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;   // a self-referencing cause is not a loop here
        }
        return chain.toString();
    }

    /**
     * The lamp itself, named {@code kafkaListeners} because the bean name IS the key under
     * {@code /actuator/health} and the group membership in {@code application.properties} refers to it
     * by that name. Declared as the concrete type rather than as {@link HealthIndicator}: the class
     * also carries {@code @EventListener} methods (the loop's heartbeat), and a bean whose declared
     * type hides them is a bean whose events quietly go nowhere.
     */
    @Bean
    SagaListenersHealth kafkaListeners(
            ObjectProvider<KafkaListenerEndpointRegistry> registry,
            @Value("${comments.saga.listener-stall-seconds:150}") long stallSeconds,
            @Value("${comments.kafka-enabled:false}") boolean kafkaEnabled) {
        return new SagaListenersHealth(registry.getIfAvailable(), flooredStall(stallSeconds),
                kafkaEnabled);
    }

    /**
     * Configuration validated with a floor and a message that names the variable and the fix — the
     * house pattern (microservice-offboarding's {@code longEnv}/{@code flooredStall}), because a
     * tolerance below the retry budget is not a preference, it is a lamp that lies.
     */
    static Duration flooredStall(long configuredSeconds) {
        Duration configured = Duration.ofSeconds(configuredSeconds);
        if (configured.compareTo(STALL_FLOOR) >= 0) {
            return configured;
        }
        throw new IllegalArgumentException(STALL_ENV + "=" + configuredSeconds + " is below the "
                + STALL_FLOOR.toSeconds() + "s floor: one saga command may legitimately hold the"
                + " consumer thread for a whole " + SagaRetryBudget.BUDGET.toSeconds()
                + "s retry budget plus the connection timeouts of the attempts that open and close it,"
                + " so a smaller tolerance would report every retried purge as a stalled listener."
                + " Raise it or leave it unset.");
    }

    /**
     * The confirmation channel, over the outbox {@link CommentsOutboxConfig} already wires for the
     * cascade's COMMENTS_DELETED — the same table, the same republisher, the same guarantee. This bean
     * IS the answer to "would the same treatment be cheap here": one class and one bean.
     */
    @Bean
    @ConditionalOnProperty(name = "comments.kafka-enabled", havingValue = "true")
    PurgeConfirmations purgeConfirmations(SpringOutbox commentEventsOutbox, ObjectMapper mapper) {
        return new PurgeConfirmations(commentEventsOutbox, mapper);
    }
}
