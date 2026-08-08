package com.jrobertgardzinski.comments.infrastructure;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.comments.application.MarkUserCommentsForErasure;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import com.jrobertgardzinski.comments.application.RestoreUserComments;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The listener's hygiene around broken commands: a PURGE_USER_CONTENT without an e-mail names
 * nobody — purging "" would be a no-op and confirming it would tell the orchestrator a purge
 * happened that never did. Such a command is logged and dropped, unconfirmed. And the logging
 * itself is scrutinised: purge commands carry the leaver's e-mail (PII), which must never end up
 * in a log line — not even when the payload is malformed and gets dropped with a WARN.
 */
class PurgeCommandsListenerTest {

    private final MarkUserCommentsForErasure markForErasure = mock(MarkUserCommentsForErasure.class);
    private final RestoreUserComments restoreUserComments = mock(RestoreUserComments.class);
    private final PurgeUserComments purgeUserComments = mock(PurgeUserComments.class);
    private final PurgeConfirmations confirmations = mock(PurgeConfirmations.class);
    private final PurgeCommandsListener listener = new PurgeCommandsListener(markForErasure,
            restoreUserComments, purgeUserComments, confirmations, new ObjectMapper(),
            NoTransactions.template());

    private final ListAppender<ILoggingEvent> logLines = new ListAppender<>();

    @BeforeEach
    void tapTheLog() {
        logLines.start();
        listenerLogger().addAppender(logLines);
    }

    @AfterEach
    void untapTheLog() {
        listenerLogger().detachAppender(logLines);
    }

    private static Logger listenerLogger() {
        return (Logger) LoggerFactory.getLogger(PurgeCommandsListener.class);
    }

    @Test
    @DisplayName("a purge command without an email is dropped: no purge, no confirmation")
    void missing_email_is_dropped_without_confirmation() throws Exception {
        listener.receive("{\"type\":\"PURGE_USER_CONTENT\",\"sagaId\":\"s-1\"}", null);
        verifyNoInteractions(markForErasure, restoreUserComments, purgeUserComments, confirmations);
    }

    @Test
    @DisplayName("a purge command with a blank email is dropped the same way")
    void blank_email_is_dropped_without_confirmation() throws Exception {
        listener.receive("{\"type\":\"PURGE_USER_CONTENT\",\"sagaId\":\"s-2\",\"email\":\"\"}", null);
        verifyNoInteractions(markForErasure, restoreUserComments, purgeUserComments, confirmations);
    }

    @Test
    @DisplayName("a malformed payload is dropped WITHOUT echoing it — it may carry an e-mail")
    void malformed_payload_is_not_echoed_into_the_log() throws Exception {
        listener.receive("not json at all, but with leaver@example.com inside", null);

        verifyNoInteractions(markForErasure, restoreUserComments, purgeUserComments, confirmations);
        assertTrue(logLines.list.stream().anyMatch(event ->
                        event.getFormattedMessage().contains("malformed")),
                "the drop must still leave a trace in the log");
        assertFalse(logLines.list.stream().anyMatch(event ->
                        event.getFormattedMessage().contains("leaver@example.com")),
                "the payload (with its PII) must not be echoed into the log");
    }

    @Test
    @DisplayName("an unparseable purge rule is dropped WITHOUT echoing its raw text")
    void invalid_rule_text_is_not_echoed_into_the_log() throws Exception {
        // PurgeRule.parse's message pastes the raw rule text — the WARN must not repeat it
        listener.receive("{\"type\":\"ERASE_USER_CONTENT\",\"sagaId\":\"s-5\","
                + "\"email\":\"leaver@example.com\","
                + "\"policy\":{\"comments\":\"totally bogus leaver@example.com rule\"}}", null);

        verify(purgeUserComments).execute("leaver@example.com", java.util.Optional.empty());
        assertTrue(logLines.list.stream().anyMatch(event ->
                        event.getFormattedMessage().contains("unparseable comments purge rule")),
                "the fallback to the default must still leave a trace in the log");
        assertFalse(logLines.list.stream().anyMatch(event ->
                        event.getFormattedMessage().contains("bogus")
                                || event.getFormattedMessage().contains("leaver@example.com")),
                "the raw rule text from the wire must not be echoed into the log");
    }

    @Test
    @DisplayName("a phone number in a broken rule never reaches the log — not even in digit chunks")
    void phone_number_in_rule_text_is_not_echoed_into_the_log() throws Exception {
        // the old per-character filter kept [0-9], so "+48 601 234 567" leaked as 48?601?234?567;
        // the token whitelist accepts numbers only as KEEP_POPULAR_ANONYMIZED's threshold
        listener.receive("{\"type\":\"ERASE_USER_CONTENT\",\"sagaId\":\"s-6\","
                + "\"email\":\"leaver@example.com\","
                + "\"policy\":{\"comments\":\"call me +48 601 234 567\"}}", null);

        verify(purgeUserComments).execute("leaver@example.com", java.util.Optional.empty());
        assertTrue(logLines.list.stream().anyMatch(event ->
                        event.getFormattedMessage().contains("unparseable comments purge rule")),
                "the fallback to the default must still leave a trace in the log");
        assertFalse(logLines.list.stream().anyMatch(event ->
                        event.getFormattedMessage().contains("48")
                                || event.getFormattedMessage().contains("601")
                                || event.getFormattedMessage().contains("234")
                                || event.getFormattedMessage().contains("567")),
                "no chunk of the phone number may survive into the log");
    }

    @Test
    @DisplayName("a PESEL in a broken rule never reaches the log")
    void pesel_in_rule_text_is_not_echoed_into_the_log() throws Exception {
        // eleven digits — the old filter passed all of them; ≤4-digit thresholds are only
        // vocabulary straight after KEEP_POPULAR_ANONYMIZED:, so a bare number collapses to ?
        listener.receive("{\"type\":\"ERASE_USER_CONTENT\",\"sagaId\":\"s-7\","
                + "\"email\":\"leaver@example.com\","
                + "\"policy\":{\"comments\":\"90010112345\"}}", null);

        verify(purgeUserComments).execute("leaver@example.com", java.util.Optional.empty());
        assertFalse(logLines.list.stream().anyMatch(event ->
                        event.getFormattedMessage().contains("90010112345")
                                || event.getFormattedMessage().contains("9001")),
                "neither the PESEL nor any prefix of it may survive into the log");
    }

    @Test
    @DisplayName("an UPPERCASE e-mail in a broken rule never reaches the log")
    void uppercase_email_in_rule_text_is_not_echoed_into_the_log() throws Exception {
        // the old filter kept [A-Z_], so LEAVER@EXAMPLE.COM leaked as LEAVER?EXAMPLE?COM;
        // whole-token whitelisting reduces every non-vocabulary word to ?
        listener.receive("{\"type\":\"ERASE_USER_CONTENT\",\"sagaId\":\"s-8\","
                + "\"email\":\"leaver@example.com\","
                + "\"policy\":{\"comments\":\"LEAVER@EXAMPLE.COM\"}}", null);

        verify(purgeUserComments).execute("leaver@example.com", java.util.Optional.empty());
        assertFalse(logLines.list.stream().anyMatch(event ->
                        event.getFormattedMessage().contains("LEAVER")
                                || event.getFormattedMessage().contains("EXAMPLE")
                                || event.getFormattedMessage().contains("COM")),
                "no fragment of a shouted e-mail may survive into the log");
    }

    @Test
    @DisplayName("a successful mark logs the saga id, never the leaver's e-mail")
    void successful_purge_keeps_the_email_out_of_the_log() throws Exception {
        listener.receive("{\"type\":\"PURGE_USER_CONTENT\",\"sagaId\":\"s-3\","
                + "\"email\":\"leaver@example.com\"}", null);

        verify(markForErasure).execute("leaver@example.com");
        assertTrue(logLines.list.stream().anyMatch(event ->
                        event.getFormattedMessage().contains("s-3")),
                "the saga id identifies the run in the log");
        assertFalse(logLines.list.stream().anyMatch(event ->
                        event.getFormattedMessage().contains("leaver@example.com")),
                "the leaver's e-mail is PII and stays out of every log line");
    }

    @Test
    @DisplayName("a completed mark confirms the SAME saga it was commanded for — and erases nothing")
    void a_completed_purge_confirms_its_own_saga() throws Exception {
        listener.receive("{\"type\":\"PURGE_USER_CONTENT\",\"sagaId\":\"s-9\","
                + "\"email\":\"leaver@example.com\"}", null);

        InOrder order = inOrder(markForErasure, confirmations);
        // the mark first, the promise to report it second, both inside one transaction: a
        // confirmation announced before the mark would be a lie the outbox then made durable
        order.verify(markForErasure).execute("leaver@example.com");
        order.verify(confirmations).confirm("s-9", "leaver@example.com");
        // and the point of the two-phase design: the reversible command destroys nothing
        verifyNoInteractions(purgeUserComments);
    }

    @Test
    @DisplayName("the closure erases, and is NOT confirmed — the orchestrator has already decided")
    void the_closure_erases_what_the_mark_reserved() throws Exception {
        listener.receive("{\"type\":\"ERASE_USER_CONTENT\",\"sagaId\":\"s-11\","
                + "\"email\":\"leaver@example.com\"}", null);

        verify(purgeUserComments).execute("leaver@example.com", java.util.Optional.empty());
        verifyNoInteractions(markForErasure, restoreUserComments, confirmations);
    }

    @Test
    @DisplayName("the compensation restores, erases nothing and is not confirmed either")
    void the_compensation_restores() throws Exception {
        listener.receive("{\"type\":\"RESTORE_USER_CONTENT\",\"sagaId\":\"s-12\","
                + "\"email\":\"leaver@example.com\"}", null);

        verify(restoreUserComments).execute("leaver@example.com");
        verifyNoInteractions(markForErasure, purgeUserComments, confirmations);
    }

    @Test
    @DisplayName("a command type this participant does not know is ignored, not guessed at")
    void an_unknown_command_type_is_ignored() throws Exception {
        listener.receive("{\"type\":\"SOMETHING_ELSE\",\"sagaId\":\"s-13\","
                + "\"email\":\"leaver@example.com\"}", null);

        verifyNoInteractions(markForErasure, restoreUserComments, purgeUserComments, confirmations);
    }

    @Test
    @DisplayName("a mark that fails confirms nothing and lets the failure out — so Kafka redelivers")
    void a_failed_purge_confirms_nothing() {
        doThrow(new IllegalStateException("the store is down"))
                .when(markForErasure).execute("leaver@example.com");

        assertThrows(IllegalStateException.class, () ->
                listener.receive("{\"type\":\"PURGE_USER_CONTENT\",\"sagaId\":\"s-10\","
                        + "\"email\":\"leaver@example.com\"}", null));

        // the failure must reach the container: that is what makes SagaRetryBudget retry the record
        // instead of the offset being committed over a purge that did not happen
        verifyNoInteractions(confirmations);
        assertFalse(logLines.list.stream().anyMatch(event ->
                        event.getFormattedMessage().contains("leaver@example.com")),
                "not even on the failure path does the address reach a log line");
    }
}
