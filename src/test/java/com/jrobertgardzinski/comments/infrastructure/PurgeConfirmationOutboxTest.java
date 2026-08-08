package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.comments.application.MarkUserCommentsForErasure;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import com.jrobertgardzinski.comments.application.RestoreUserComments;
import com.jrobertgardzinski.outbox.OutboxRepublisher;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The saga's confirmation now that it rides the outbox — against a REAL table (the library's own DDL,
 * which is the string {@code V4__comment_events_outbox.sql} was copied from) and a REAL transaction
 * manager, because every property worth having here is a property of a row and a commit.
 *
 * <p>What the old {@code kafka.send(...).whenComplete(log)} could not promise: Spring Kafka commits the
 * command's offset as soon as the listener returns, so a send still sitting in the producer's
 * accumulator when the process died took the confirmation with it — comments purged, orchestrator none
 * the wiser, three re-commands later the saga compensates and the leaver gets their account back
 * without their content plus an apology mail. An ERROR line was the only trace, and only if the JVM
 * lived long enough to write it.
 *
 * <p>Sibling of {@code microservice-memes}' test of the same name, on purpose: after this round the two
 * Spring participants make the same promise, so they had better be provable by the same tests.
 */
class PurgeConfirmationOutboxTest {

    private static final String LEAVER = "leaver@example.com";
    private static final String SAGA = "5c1c7e3d-9b41-4b32-8f0a-7a3f2d1e5b90";
    private static final String COMMAND = "{\"type\":\"PURGE_USER_CONTENT\",\"email\":\"" + LEAVER
            + "\",\"sagaId\":\"" + SAGA + "\"}";

    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private final PurgeUserComments purgeUserComments = mock(PurgeUserComments.class);
    private final MarkUserCommentsForErasure markForErasure = mock(MarkUserCommentsForErasure.class);

    private final OutboxTestDatabase db = OutboxTestDatabase.with(kafka);

    private final PurgeCommandsListener listener = new PurgeCommandsListener(
            markForErasure, mock(RestoreUserComments.class), purgeUserComments,
            new PurgeConfirmations(db.outbox(), mapper), mapper, db.tx());

    private OutboxRepublisher republisher() {
        // exactly the republisher CommentsOutboxConfig wires, on this fixture's outbox
        return CommentsOutboxConfig.republisher(db.outbox(), 24);
    }

    @Test
    @DisplayName("a send the broker never confirms leaves the confirmation OWED — and the republisher pays it")
    void an_unconfirmed_confirmation_is_kept_and_re_sent() throws Exception {
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        listener.receive(COMMAND, "cid-of-the-deletion");

        verify(markForErasure).execute(LEAVER);
        ArgumentCaptor<ProducerRecord<String, String>> firstTry =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(firstTry.capture());
        assertTrue(db.isPending(SAGA), "an unconfirmed send is not a delivery: the row keeps the"
                + " obligation, which is what the old fire-and-forget could not do");

        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        db.backdateBeyondMinAge(SAGA);
        republisher().runOnce();

        ArgumentCaptor<ProducerRecord<String, String>> redelivered =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(2)).send(redelivered.capture());
        assertEquals(firstTry.getValue().value(), redelivered.getValue().value(),
                "the redelivery is the SAME confirmation, byte for byte — the row IS the record");
        assertTrue(db.isPublished(SAGA), "and only a confirmed delivery marks it");

        republisher().runOnce();
        verifyNoMoreInteractions(kafka);   // delivered means done
    }

    @Test
    @DisplayName("the confirmation goes out on comments-events, keyed by the saga, carrying the trace")
    void the_confirmation_is_addressed_the_way_the_orchestrator_expects() throws Exception {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        listener.receive(COMMAND, "cid-of-the-deletion");

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(sent.capture());
        ProducerRecord<String, String> confirmation = sent.getValue();

        assertEquals(KafkaCommentEvents.TOPIC, confirmation.topic(),
                "microservice-offboarding subscribes here — see PurgeConfirmationTopicTest");
        assertEquals(SAGA, confirmation.key(),
                "keyed by the saga run and no longer by the address: event_key is varchar(64) and an"
                        + " address may be 254 characters — and a key is broker-visible metadata");
        assertNotNull(confirmation.headers().lastHeader(KafkaTracing.HEADER),
                "the cid rides along, stored in the row so even a republication hours later has it");
        assertEquals("cid-of-the-deletion", new String(
                confirmation.headers().lastHeader(KafkaTracing.HEADER).value(), StandardCharsets.UTF_8));

        JsonNode payload = mapper.readTree(confirmation.value());
        assertEquals("USER_CONTENT_PURGED", payload.path("type").asText());
        assertEquals(SAGA, payload.path("sagaId").asText());
        assertEquals(LEAVER, payload.path("email").asText(), "the orchestrator matches on the address");
        assertEquals(1, payload.path("version").asInt(), "envelope version 1 (ADR 0004)");
    }

    @Test
    @DisplayName("a rolled-back purge leaves NO confirmation — the row shares the erasure's fate")
    void a_rolled_back_purge_confirms_nothing() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        // the listener JOINS an outer transaction (Spring's default propagation), so rolling that one
        // back is the honest way to stage "the erasure did not survive" from the outside
        db.tx().executeWithoutResult(status -> {
            try {
                listener.receive(COMMAND, null);
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
            status.setRollbackOnly();
        });

        assertEquals(0, db.rows(),
                "a confirmation that outlived its purge would tell the orchestrator to delete an"
                        + " account whose comments are still signed with its name");
        verifyNoMoreInteractions(kafka);   // the parked first attempt is dropped with the rollback
    }

    @Test
    @DisplayName("a purge that throws writes nothing at all and lets the failure out to the container")
    void a_failing_purge_writes_nothing() {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("no database"))
                .when(markForErasure).execute(LEAVER);

        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> listener.receive(COMMAND, null));

        assertEquals(0, db.rows());
        verifyNoMoreInteractions(kafka);
        assertFalse(db.isPublished(SAGA), "nothing was written, so nothing can be published");
    }
}
