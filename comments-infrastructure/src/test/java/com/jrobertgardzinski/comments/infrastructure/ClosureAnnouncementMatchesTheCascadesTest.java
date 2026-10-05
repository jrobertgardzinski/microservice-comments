package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jrobertgardzinski.closure.ClosureMessages;
import com.jrobertgardzinski.comments.system.erasure.MarkUserCommentsForErasure;
import com.jrobertgardzinski.comments.system.erasure.PurgeUserComments;
import com.jrobertgardzinski.comments.system.erasure.RestoreUserComments;
import com.jrobertgardzinski.comments.closure.CommentsClosureParticipant;
import com.jrobertgardzinski.deletion.DeletionMessages;
import com.jrobertgardzinski.observation.Observations;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * This service has TWO producers of COMMENTS_DELETED, and until this file only one of them was
 * checked from the transport side. The cascade's — a meme went, its thread went with it — is
 * verified against microservice-user-collections' committed pact by
 * {@link CommentsDeletedPactProviderTest}. The account closure's, added when the ERASE hop started
 * announcing the comments it destroys, goes the same road: the same adapter, the same outbox, the
 * same topic, the same consumer. Nothing said so.
 *
 * <p>So it is said here: the record the CLOSURE hands the broker is the record the pact pins,
 * modulo the envelope id that is minted per event. Same topic, same partition key, same field set,
 * same values — proven by driving both production paths in one test and comparing what comes out of
 * the Kafka template, so a change to either producer alone turns this red.
 *
 * <p><b>Why a comparison and not a second provider verification.</b> pact-jvm resolves a
 * {@code @PactVerifyProvider} handler by scanning the classpath for the interaction's DESCRIPTION,
 * and the consumer states one COMMENTS_DELETED interaction. Two handlers answering to it in one
 * classpath make the choice between them arbitrary — which is not a theory: a second provider test
 * beside the first failed with the verifier reaching into
 * {@code CommentsDeletedPactProviderTest} instead of its own class, and the day it had picked the
 * other way round the CASCADE's verification would have been the one quietly proving the wrong
 * record. One handler, and the second producer pinned to it, keeps both honest. If collections ever
 * states a closure-specific provider state of its own, this becomes a second handler over there
 * instead.
 *
 * <p>The seam is the same one the pact test uses: {@link PurgeUserComments} is stubbed to REPORT
 * what it destroyed, because the repository behind it is a database and the report is its whole
 * contract with {@link CommentsClosureParticipant}. Everything from there out — the participant's
 * unit of work, the envelope, the key, the outbox row on a real H2, the topic — is production code.
 */
@Epic("Contract")
@Feature("Comments-deleted announcement")
class ClosureAnnouncementMatchesTheCascadesTest {

    private static final String MEME = UUID.randomUUID().toString();
    private static final List<String> COMMENTS =
            List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("the closure announces the very record the cascade's pact pins")
    void the_closure_announces_the_pacted_record() throws Exception {
        ProducerRecord<String, String> cascade =
                CommentsDeletedPactProviderTest.realAnnouncement(MEME, COMMENTS);

        ProducerRecord<String, String> closure = closureAnnouncement(Map.of(MEME, COMMENTS));

        // the topic first: the audit of 26.07 called an unasserted topic name the system's most
        // dangerous structural gap, and the pact only ever saw the cascade's half of this one
        assertEquals(cascade.topic(), closure.topic());
        // the key, because the whole cascade of one meme stays on one partition — a closure that
        // keyed by the leaver instead would let a consumer see the comments' fate out of order
        assertEquals(cascade.key(), closure.key());
        assertEquals(MEME, closure.key());
        assertEquals(withoutTheEnvelopeId(cascade.value()), withoutTheEnvelopeId(closure.value()),
                "two producers, one message: the consumer cannot tell them apart and must not have to");
    }

    @Test
    @DisplayName("a closure that destroyed nothing announces nothing")
    void nothing_destroyed_is_nothing_announced() throws Exception {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

        // the rule kept everything, or this ERASE is a redelivery and nothing is left reserved
        listenerOver(kafka, PurgeUserComments.Purged.NOTHING).receive(eraseCommand(), null);

        // an empty announcement states no fact, and would be repeated on every redelivery
        verifyNoInteractions(kafka);
    }

    /**
     * The envelope id is minted per event and is exactly what a consumer deduplicates on, so the
     * two records must NOT agree on it; everything else about them has to.
     */
    private String withoutTheEnvelopeId(String payload) throws Exception {
        ObjectNode event = (ObjectNode) mapper.readTree(payload);
        assertEquals(true, event.hasNonNull(DeletionMessages.Field.ID),
                "the announcement carries an envelope id, or the republisher's duplicate is undetectable");
        event.remove(DeletionMessages.Field.ID);
        return mapper.writeValueAsString(event);
    }

    /** The record the real closure hands the broker after erasing a leaver's marked comments. */
    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> closureAnnouncement(Map<String, List<String>> deleted)
            throws Exception {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        listenerOver(kafka, new PurgeUserComments.Purged(deleted)).receive(eraseCommand(), null);

        ArgumentCaptor<ProducerRecord<String, String>> sent =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(sent.capture());
        return sent.getValue();
    }

    /** The production wiring of the ERASE hop, over a mock broker and a real outbox. */
    private PurgeCommandsListener listenerOver(KafkaTemplate<String, String> kafka,
                                               PurgeUserComments.Purged report) {
        PurgeUserComments purgeUserComments = mock(PurgeUserComments.class);
        when(purgeUserComments.execute(any(), any())).thenReturn(report);
        OutboxTestDatabase db = OutboxTestDatabase.with(kafka);
        return new PurgeCommandsListener(mock(MarkUserCommentsForErasure.class),
                mock(RestoreUserComments.class), purgeUserComments,
                new KafkaCommentEvents(db.outbox(), mapper), mock(PurgeConfirmations.class),
                Observations.silent(), mapper, db.tx());
    }

    /**
     * The orchestrator's closing command, as it arrives: the irreversible half, addressed to a
     * leaver by identity. A SELF closure, so no rule rides along and everything marked goes.
     */
    private String eraseCommand() {
        return "{\"" + ClosureMessages.Field.TYPE + "\":\"" + ClosureMessages.ERASE_USER_CONTENT
                + "\",\"" + ClosureMessages.Field.SAGA_ID + "\":\"" + UUID.randomUUID()
                + "\",\"" + ClosureMessages.Field.USER_ID + "\":\"" + UUID.randomUUID()
                + "\",\"" + ClosureMessages.Field.INITIATED_BY + "\":\"SELF\"}";
    }
}
