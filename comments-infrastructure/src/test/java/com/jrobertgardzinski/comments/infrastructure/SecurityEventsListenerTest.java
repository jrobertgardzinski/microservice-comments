package com.jrobertgardzinski.comments.infrastructure;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.comments.application.RekeyUserComments;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The rename listener's guardrails — the half of its behaviour the pact cannot state, because it is
 * about records the pact deliberately says nothing about.
 *
 * <p>{@code security-events} is a shared facts topic: most of what lands on it is another service's
 * business, and this one has to let it pass without a word. The rest is the usual discipline for a
 * listener on a topic where every record names a person — a malformed payload is reported by its
 * size, never by its content, and a fact that cannot be carried out is dropped by its id.
 */
@Epic("Saga")
@Feature("Address changes")
class SecurityEventsListenerTest {

    private static final String SOMEBODY = "leaver@example.com";

    private final RekeyUserComments rekeyUserComments = mock(RekeyUserComments.class);
    private final SecurityEventsListener listener =
            new SecurityEventsListener(rekeyUserComments, new ObjectMapper(), NoTransactions.template());

    private final Logger listenerLog = (Logger) LoggerFactory.getLogger(SecurityEventsListener.class);
    private final ListAppender<ILoggingEvent> written = new ListAppender<>();

    @BeforeEach
    void captureTheLog() {
        written.start();
        listenerLog.addAppender(written);
    }

    @AfterEach
    void releaseTheLog() {
        listenerLog.detachAppender(written);
        written.stop();
    }

    @Test
    @DisplayName("a deletion request on the same topic is somebody else's fact — ignored, and in silence")
    void another_services_fact_is_ignored_without_a_word() throws Exception {
        // this service hears about a deletion as a purge COMMAND from the orchestrator, on another
        // topic; a line per deletion request in the portal would be noise that teaches an operator
        // to stop reading this log
        listener.receive("{\"id\":\"9f0d1c2b-3a4e-5d6f-8091-a2b3c4d5e6f7\","
                + "\"type\":\"ACCOUNT_DELETION_REQUESTED\",\"email\":\"" + SOMEBODY + "\","
                + "\"version\":1}", null);

        verifyNoInteractions(rekeyUserComments);
        assertTrue(logLines().isEmpty(), "nothing to say about it: " + logLines());
    }

    @Test
    @DisplayName("a rename missing either address is dropped by its id — no half a move")
    void a_rename_without_both_addresses_is_dropped() throws Exception {
        listener.receive("{\"id\":\"1b2c3d4e-5f60-7182-93a4-b5c6d7e8f901\",\"type\":\"EMAIL_CHANGED\","
                + "\"email\":\"" + SOMEBODY + "\",\"version\":1}", null);

        verifyNoInteractions(rekeyUserComments);
        assertNothingLoggedContains(SOMEBODY);
        assertTrue(logLines().stream().anyMatch(line -> line.contains("1b2c3d4e")),
                "the id is what an investigation has to go on: " + logLines());
    }

    @Test
    @DisplayName("a malformed fact is logged by its size only — the payload names two addresses")
    void a_malformed_fact_is_logged_by_size_only() throws Exception {
        String broken = "{not json, but it still names " + SOMEBODY;

        listener.receive(broken, null);

        verifyNoInteractions(rekeyUserComments);
        assertNothingLoggedContains(SOMEBODY);
        assertTrue(logLines().stream().anyMatch(line -> line.contains(String.valueOf(broken.length()))),
                "the size is enough to investigate: " + logLines());
    }

    @Test
    @DisplayName("a completed re-key is logged by count and fact id — never by either address")
    void the_addresses_stay_out_of_the_log() throws Exception {
        listener.receive("{\"id\":\"2a7f0f5a-1c4b-3e6d-8a9b-0c1d2e3f4a5b\",\"type\":\"EMAIL_CHANGED\","
                + "\"oldEmail\":\"" + SOMEBODY + "\",\"email\":\"moved@example.com\","
                + "\"version\":1}", null);

        assertNothingLoggedContains(SOMEBODY);
        assertNothingLoggedContains("moved@example.com");
        assertTrue(logLines().stream().anyMatch(line -> line.contains("2a7f0f5a")),
                "the fact id identifies the change in the log: " + logLines());
    }

    private List<String> logLines() {
        return written.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private void assertNothingLoggedContains(String forbidden) {
        assertFalse(logLines().stream().anyMatch(line -> line.contains(forbidden)),
                "\"" + forbidden + "\" must not appear in the log: " + logLines());
    }
}
