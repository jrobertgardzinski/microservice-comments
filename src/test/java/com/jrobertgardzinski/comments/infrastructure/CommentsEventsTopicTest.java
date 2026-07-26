package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.comments.application.DeleteThread;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Two very different conversations now share {@code comments-events}: the ORCHESTRATED saga's
 * confirmations (USER_CONTENT_PURGED, which microservice-offboarding is waiting for) and the
 * CHOREOGRAPHED cascade's announcements (COMMENTS_DELETED, which nobody is waiting for). Adding
 * the second must not disturb the first.
 *
 * <p>It does not, because offboarding's {@code EventsRouter} routes on {@code (topic, type)} and
 * returns nothing for a type it does not know — a tolerant reader. This test stands in for that
 * receiver: it applies the very same filter to what both producers here actually emit, and pins
 * the two properties the filter depends on — the cascade event's type is not the confirmation's,
 * and it carries neither a {@code sagaId} nor an {@code email} that could make it look like one.
 *
 * <p>Worth re-running deliberately after round 10: the cascade's half of this topic now travels
 * through an outbox and can be RE-SENT, while the saga's confirmations still go straight out. The two
 * conversations no longer even share a delivery path, and this test is what keeps the one that
 * changed from disturbing the one that did not.
 */
class CommentsEventsTopicTest {

    /** The saga receiver's rule, transcribed from microservice-offboarding's EventsRouter#handle. */
    private static boolean theSagaListensTo(JsonNode event) {
        return "USER_CONTENT_PURGED".equals(event.path("type").asText());
    }

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private final PurgeUserComments purgeUserComments = mock(PurgeUserComments.class);
    private final DeleteThread deleteThread = mock(DeleteThread.class);

    private final OutboxTestDatabase db = OutboxTestDatabase.with(kafka);

    private final PurgeCommandsListener saga =
            new PurgeCommandsListener(purgeUserComments, kafka, mapper);
    private final MemesEventsListener cascade = new MemesEventsListener(deleteThread,
            new KafkaCommentEvents(db.outbox(), mapper), mapper, db.tx());

    @BeforeEach
    void sendSucceeds() {
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    @DisplayName("the cascade's new type shares the topic without disturbing the saga's traffic")
    void the_new_type_is_invisible_to_the_saga_receiver() throws Exception {
        String sagaId = UUID.randomUUID().toString();
        String memeId = UUID.randomUUID().toString();
        String commentId = UUID.randomUUID().toString();
        when(deleteThread.execute(memeId)).thenReturn(List.of(commentId));

        saga.receive("{\"type\":\"PURGE_USER_CONTENT\",\"sagaId\":\"" + sagaId
                + "\",\"email\":\"leaver@example.com\"}", null);
        cascade.receive("{\"type\":\"MEME_DELETED\",\"memeId\":\"" + memeId + "\"}", null);

        List<JsonNode> topic = whatWentOnTheTopic(2);

        List<JsonNode> forTheSaga = topic.stream().filter(CommentsEventsTopicTest::theSagaListensTo).toList();
        assertEquals(1, forTheSaga.size(),
                "the saga's receiver must still see exactly its one confirmation");
        assertEquals(sagaId, forTheSaga.get(0).path("sagaId").asText(),
                "and it must be the confirmation of the purge that just ran");

        JsonNode cascadeEvent = topic.stream().filter(event -> !theSagaListensTo(event)).findFirst()
                .orElseThrow(() -> new AssertionError("the cascade announced nothing"));
        assertEquals("COMMENTS_DELETED", cascadeEvent.path("type").asText());
        assertEquals(memeId, cascadeEvent.path("memeId").asText());
        // the two fields the saga's router reads off a confirmation: absent here, so a
        // COMMENTS_DELETED can never be mistaken for one even by a sloppier filter
        assertFalse(cascadeEvent.has("sagaId"), "the cascade knows nothing of any saga");
        assertFalse(cascadeEvent.has("email"),
                "and it names comments, not people — no PII joins the topic");
    }

    @Test
    @DisplayName("both conversations ride the same topic, keyed by what each is about")
    void each_event_is_keyed_by_its_own_subject() throws Exception {
        String memeId = UUID.randomUUID().toString();
        when(deleteThread.execute(memeId)).thenReturn(List.of(UUID.randomUUID().toString()));

        saga.receive("{\"type\":\"PURGE_USER_CONTENT\",\"sagaId\":\"" + UUID.randomUUID()
                + "\",\"email\":\"leaver@example.com\"}", null);
        cascade.receive("{\"type\":\"MEME_DELETED\",\"memeId\":\"" + memeId + "\"}", null);

        List<ProducerRecord<String, String>> records = sentRecords(2);
        assertTrue(records.stream().allMatch(record -> "comments-events".equals(record.topic())),
                "one topic per producing service — the type tells the traffic apart");
        assertEquals("leaver@example.com", records.get(0).key(),
                "a saga confirmation is about an account, so it is keyed by it");
        assertEquals(memeId, records.get(1).key(),
                "a cascade announcement is about a meme, so it is keyed by it");
    }

    private List<ProducerRecord<String, String>> sentRecords(int expected) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> sent =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(expected)).send(sent.capture());
        return sent.getAllValues();
    }

    private List<JsonNode> whatWentOnTheTopic(int expected) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (ProducerRecord<String, String> record : sentRecords(expected)) {
            events.add(mapper.readTree(record.value()));
        }
        return events;
    }
}
