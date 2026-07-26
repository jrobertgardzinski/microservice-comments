package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.comments.application.DeleteThread;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The cascade's second hop. microservice-memes announces that a meme is gone; only THIS service
 * knows which comments hung under it, so only this service can tell microservice-user-collections
 * which saved comments just became dead references. These tests pin the announcement's contract —
 * and, just as hard, the three cases where silence is the contract: an empty thread, a rolled-back
 * cascade, and an event that is not a deletion.
 */
class MemeDeletedCascadeTest {

    private final DeleteThread deleteThread = mock(DeleteThread.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final MemesEventsListener listener =
            new MemesEventsListener(deleteThread, new KafkaCommentEvents(kafka, mapper), mapper);

    private void sendSucceeds() {
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    private ProducerRecord<String, String> announced() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> sent =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(sent.capture());
        return sent.getValue();
    }

    private static String memeDeleted(String memeId) {
        return "{\"type\":\"MEME_DELETED\",\"memeId\":\"" + memeId + "\"}";
    }

    @Test
    @DisplayName("a dropped thread is announced on comments-events with every id it took")
    void the_whole_thread_is_named() throws Exception {
        sendSucceeds();
        String memeId = UUID.randomUUID().toString();
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        when(deleteThread.execute(memeId)).thenReturn(List.of(first, second));

        listener.receive(memeDeleted(memeId), null);

        ProducerRecord<String, String> record = announced();
        assertEquals("comments-events", record.topic());
        assertEquals(memeId, record.key(),
                "keyed by the meme, so the whole cascade of one meme stays on one partition");

        JsonNode event = mapper.readTree(record.value());
        assertEquals("COMMENTS_DELETED", event.path("type").asText());
        assertEquals(memeId, event.path("memeId").asText());
        List<String> announcedIds = new ArrayList<>();
        event.path("commentIds").forEach(id -> announcedIds.add(id.asText()));
        assertEquals(List.of(first, second), announcedIds,
                "the complete set of ids — collections has no other way of learning them");
        assertEquals(1, event.path("version").asInt(), "envelope v1 (workspace ADR 0004)");
        assertDoesNotThrow(() -> UUID.fromString(event.path("id").asText()),
                "every envelope in the portal carries a uuid of its own");
    }

    @Test
    @DisplayName("a meme nobody commented on is announced to nobody — no empty list")
    void an_empty_thread_says_nothing() throws Exception {
        String memeId = UUID.randomUUID().toString();
        when(deleteThread.execute(memeId)).thenReturn(List.of());

        listener.receive(memeDeleted(memeId), null);

        // an empty COMMENTS_DELETED states no fact a consumer could act on, and the cascade is
        // idempotent — every redelivery of MEME_DELETED would re-emit it forever
        verifyNoInteractions(kafka);
    }

    @Test
    @DisplayName("a rolled-back cascade announces nothing: the comments are still there")
    void a_rollback_never_lets_the_event_out() {
        String memeId = UUID.randomUUID().toString();
        when(deleteThread.execute(memeId))
                .thenThrow(new DataAccessResourceFailureException("the thread delete blew up"));

        // the transaction lives inside the (decorated) use case, so a rollback surfaces here as an
        // exception — the listener never reaches the announcement, and Kafka redelivers the event
        assertThrows(DataAccessResourceFailureException.class,
                () -> listener.receive(memeDeleted(memeId), null));
        verifyNoInteractions(kafka);
    }

    @Test
    @DisplayName("an event that is not a deletion drops no thread and announces nothing")
    void other_meme_events_are_ignored() {
        listener.receive("{\"type\":\"MEME_PUBLISHED\",\"memeId\":\"m-1\"}", null);

        verifyNoInteractions(deleteThread, kafka);
    }

    @Test
    @DisplayName("the trace of the deletion continues onto the announcement")
    void the_correlation_id_rides_the_second_hop() {
        sendSucceeds();
        String memeId = UUID.randomUUID().toString();
        when(deleteThread.execute(memeId)).thenReturn(List.of(UUID.randomUUID().toString()));

        listener.receive(memeDeleted(memeId), "cid-of-the-deletion");

        // one request deleted the meme; the whole cascade must be greppable under its cid
        assertEquals("cid-of-the-deletion", new String(
                announced().headers().lastHeader(KafkaTracing.HEADER).value(), StandardCharsets.UTF_8));
    }
}
