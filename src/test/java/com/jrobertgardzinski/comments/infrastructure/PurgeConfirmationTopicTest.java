package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * WHERE the saga's confirmation goes — the half of the contract the committed pact cannot see.
 *
 * <p>The audit of 26.07 spelled the gap out: the provider pact answers with the payload alone, so it
 * pins the SHAPE and throws the topic away. A confirmation with a perfect shape published on
 * {@code comment-events}, {@code comments-events-v2} or the commands topic would satisfy every pact,
 * every unit test and every green dashboard, while microservice-offboarding waited 120 seconds,
 * re-commanded three times, capitulated, and handed each leaver back an account whose comments this
 * service had already dealt with. {@link CascadeTopicNamesTest} pins the cascade's two names for the
 * same reason; this is the third name in the same family, the saga's.
 *
 * <p>The twin literal lives in <b>microservice-offboarding</b>, in {@code Main.DEFAULT_PARTICIPANTS}
 * ({@code comments=comments-events}) and in the deployment's {@code OFFBOARDING_PARTICIPANTS}.
 * Changing the string here REQUIRES changing it there.
 */
class PurgeConfirmationTopicTest {

    /** See the class comment: the twin literal lives in microservice-offboarding. */
    private static final String COMMENTS_EVENTS = "comments-events";

    private static final String SAGA = "9f1d3c7a-0b52-4c1e-8d33-1e2f3a4b5c6d";

    /** The real listener, the real confirmation builder, the real mapping onto a producer record. */
    private static ProducerRecord<String, String> realConfirmation() throws Exception {
        CapturedConfirmations confirmations = new CapturedConfirmations();
        new PurgeCommandsListener(mock(PurgeUserComments.class), confirmations, new ObjectMapper(),
                NoTransactions.template())
                .receive("{\"type\":\"PURGE_USER_CONTENT\",\"sagaId\":\"" + SAGA + "\","
                        + "\"email\":\"leaver@example.com\"}", null);
        // the same mapping the outbox's dispatch performs on the stored row, first attempt or
        // republication alike — a topic mangled anywhere on that way would still pass a constant check
        return KafkaCommentDispatch.toRecord(confirmations.captured());
    }

    @Test
    @DisplayName("the confirmation is published on the topic the orchestrator listens to for this participant")
    void the_confirmation_goes_to_the_agreed_topic() throws Exception {
        assertEquals(COMMENTS_EVENTS, realConfirmation().topic(),
                "microservice-offboarding maps this topic to the 'comments' participant — see the"
                        + " class comment before changing it");
        assertEquals(COMMENTS_EVENTS, KafkaCommentEvents.TOPIC,
                "one topic per producing service: the cascade's COMMENTS_DELETED and the saga's"
                        + " confirmations share it, and consumers tell them apart by type");
    }

    @Test
    @DisplayName("and it is keyed by the saga run, which the outbox's 64-char key column can hold")
    void the_confirmation_is_keyed_by_the_saga() throws Exception {
        assertEquals(SAGA, realConfirmation().key(),
                "the old send keyed it by the leaver's address: 254 characters are legal and the"
                        + " outbox's event_key is varchar(64), so a long address would have failed the"
                        + " INSERT inside the purge's own transaction");
    }
}
