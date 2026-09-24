package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.config.PurgeRule;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.comments.application.MarkUserCommentsForErasure;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import com.jrobertgardzinski.comments.application.RestoreUserComments;
import com.jrobertgardzinski.comments.closure.CommentsClosureParticipant;
import com.jrobertgardzinski.comments.domain.Observation;
import com.jrobertgardzinski.observation.Observations;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The listener's hygiene around broken commands: a PURGE_USER_CONTENT without an e-mail names
 * nobody — purging "" would be a no-op and confirming it would tell the orchestrator a purge
 * happened that never did. Such a command is logged and dropped, unconfirmed. And the logging
 * itself is scrutinised: purge commands carry the leaver's e-mail (PII), which must never end up
 * in a log line — not even when the payload is malformed and gets dropped with a WARN.
 */
@Epic("Saga")
@Feature("Purge command handling")
class PurgeCommandsListenerTest {

    private final MarkUserCommentsForErasure markForErasure = mock(MarkUserCommentsForErasure.class);
    private final RestoreUserComments restoreUserComments = mock(RestoreUserComments.class);
    private final PurgeUserComments purgeUserComments = mock(PurgeUserComments.class);
    private final PurgeConfirmations confirmations = mock(PurgeConfirmations.class);
    private final java.util.List<Observation> observed = new java.util.ArrayList<>();
    private final Observations<Observation> observations = observed::add;
    private final PurgeCommandsListener listener = new PurgeCommandsListener(markForErasure,
            restoreUserComments, purgeUserComments, confirmations, observations, new ObjectMapper(),
            NoTransactions.template());

    private final ListAppender<ILoggingEvent> logLines = new ListAppender<>();

    @BeforeEach
    void tapTheLog() {
        logLines.start();
        tappedLoggers().forEach(logger -> logger.addAppender(logLines));
    }

    @AfterEach
    void untapTheLog() {
        tappedLoggers().forEach(logger -> logger.detachAppender(logLines));
    }

    /**
     * BOTH loggers, because the two halves of this path log for different reasons: the adapter
     * says what it could not read off the wire, and the participant (comments_account-closure)
     * says what it decided. A test watching only one of them would go half blind the moment a
     * line moved across that boundary — which is exactly what happened when the participant was
     * cut out of the listener.
     */
    private static java.util.List<Logger> tappedLoggers() {
        return java.util.List.of(
                (Logger) LoggerFactory.getLogger(PurgeCommandsListener.class),
                (Logger) LoggerFactory.getLogger(CommentsClosureParticipant.class));
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
        listener.receive("{\"type\":\"ERASE_USER_CONTENT\",\"initiatedBy\":\"ADMIN\",\"sagaId\":\"s-5\","
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
        listener.receive("{\"type\":\"ERASE_USER_CONTENT\",\"initiatedBy\":\"ADMIN\",\"sagaId\":\"s-6\","
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
        listener.receive("{\"type\":\"ERASE_USER_CONTENT\",\"initiatedBy\":\"ADMIN\",\"sagaId\":\"s-7\","
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
        listener.receive("{\"type\":\"ERASE_USER_CONTENT\",\"initiatedBy\":\"ADMIN\",\"sagaId\":\"s-8\","
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
        when(markForErasure.execute("leaver@example.com")).thenReturn(3);

        listener.receive("{\"type\":\"PURGE_USER_CONTENT\",\"sagaId\":\"s-9\","
                + "\"email\":\"leaver@example.com\"}", null);

        InOrder order = inOrder(markForErasure, confirmations);
        // the mark first, the promise to report it second, both inside one transaction: a
        // confirmation announced before the mark would be a lie the outbox then made durable
        order.verify(markForErasure).execute("leaver@example.com");
        // and the confirmation carries what the mark actually reserved, not just that it ran
        order.verify(confirmations).confirm("s-9", "leaver@example.com", 3);
        // and the point of the two-phase design: the reversible command destroys nothing
        verifyNoInteractions(purgeUserComments);
        assertTrue(observed.isEmpty(), "a mark with something to reserve raises no alarm: " + observed);
    }

    @Test
    @DisplayName("a mark that reserved NOTHING is confirmed as nothing — counted and warned, never as a purge")
    void a_mark_that_found_nobody_confirms_a_zero() throws Exception {
        // the mock's default: the address on the command matched no comment. From in here that is
        // either a member who never commented or F-014 — a member whose comments are still keyed by
        // the address they used to have — and this service cannot tell the two apart, so it stops
        // claiming and starts reporting
        listener.receive("{\"type\":\"PURGE_USER_CONTENT\",\"sagaId\":\"s-14\","
                + "\"email\":\"leaver@example.com\"}", null);

        verify(confirmations).confirm("s-14", "leaver@example.com", 0);
        assertEquals(java.util.List.of(new Observation.PurgeReservedNothing()), observed,
                "an empty confirmation is the one thing only this service can count");
        assertTrue(logLines.list.stream().anyMatch(event ->
                        event.getFormattedMessage().contains("reserved NOTHING")),
                "and it says so where an operator reading the deletion's trace will see it");
        assertFalse(logLines.list.stream().anyMatch(event ->
                        event.getFormattedMessage().contains("leaver@example.com")),
                "not even the alarm names the leaver");
    }

    @Test
    @DisplayName("the closure erases, and is NOT confirmed — the orchestrator has already decided")
    void the_closure_erases_what_the_mark_reserved() throws Exception {
        listener.receive("{\"type\":\"ERASE_USER_CONTENT\",\"initiatedBy\":\"ADMIN\",\"sagaId\":\"s-11\","
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

    @Test
    @DisplayName("a closure the leaver asked for DELETES, whatever rule the command carries")
    void a_self_requested_closure_always_deletes() throws Exception {
        // the leaver is exercising the right to be forgotten and no rule may keep their words —
        // so the answer is STATED (Delete), not left empty, which would let the default answer
        listener.receive("{\"type\":\"ERASE_USER_CONTENT\",\"initiatedBy\":\"SELF\",\"sagaId\":\"s-90\","
                + "\"email\":\"leaver@example.com\","
                + "\"policy\":{\"comments\":\"ANONYMIZE_AUTHOR\"}}", null);

        verify(purgeUserComments).execute("leaver@example.com",
                java.util.Optional.of(new PurgeRule.Delete()));
    }

    @Test
    @DisplayName("a command with no initiator at all is read as the leaver's own request")
    void an_absent_initiator_is_read_as_self() throws Exception {
        listener.receive("{\"type\":\"ERASE_USER_CONTENT\",\"sagaId\":\"s-91\","
                + "\"email\":\"leaver@example.com\","
                + "\"policy\":{\"comments\":\"KEEP_POPULAR_ANONYMIZED:1\"}}", null);

        verify(purgeUserComments).execute("leaver@example.com",
                java.util.Optional.of(new PurgeRule.Delete()));
    }
}
