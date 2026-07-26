package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.comments.application.DeleteThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The cascade behind meme deletions: microservice-memes announces MEME_DELETED on
 * {@code memes-events}, and this service drops the meme's whole comment thread — eventually
 * consistent, idempotent.
 *
 * <p>And then it passes the baton on. This class is the seam where the cascade's second hop is
 * decided, because it is the only place that sees BOTH the dropped ids and the fact that the
 * transaction succeeded: {@code deleteThread.execute} returns through its transactional decorator,
 * so a return means "committed" and a rollback means an exception thrown before the announcement
 * line is ever reached. That is the whole "publish after commit" mechanism here — no outbox, no
 * transaction synchronization; {@link KafkaCommentEvents} argues why that is enough at these stakes.
 */
@Component
@ConditionalOnProperty(name = "comments.kafka-enabled", havingValue = "true")
class MemesEventsListener {

    private static final Logger LOG = LoggerFactory.getLogger(MemesEventsListener.class);

    private final DeleteThread deleteThread;
    private final CommentEvents commentEvents;
    private final ObjectMapper mapper;

    MemesEventsListener(DeleteThread deleteThread, CommentEvents commentEvents, ObjectMapper mapper) {
        this.deleteThread = deleteThread;
        this.commentEvents = commentEvents;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "memes-events", groupId = "comments")
    void receive(String payload,
                 @Header(name = KafkaTracing.HEADER, required = false) String cid) {
        if (cid != null) {
            MDC.put("cid", cid);   // continue the trace memes started when it announced the deletion
        }
        try {
            handle(payload);
        } finally {
            MDC.remove("cid");
        }
    }

    private void handle(String payload) {
        JsonNode event;
        try {
            event = mapper.readTree(payload);
        } catch (Exception malformed) {
            // NOT the payload itself: whatever arrived on the wire stays out of the logs
            // (same rule as PurgeCommandsListener) — the size is enough to investigate
            LOG.warn("dropping a malformed memes event ({} chars, not valid JSON)",
                    payload == null ? 0 : payload.length());
            return;
        }
        if ("MEME_DELETED".equals(event.path("type").asText())) {
            String memeId = event.path("memeId").asText();
            List<String> dropped = deleteThread.execute(memeId);
            LOG.info("dropped the comment thread of deleted meme {} ({} comment(s))",
                    memeId, dropped.size());
            if (dropped.isEmpty()) {
                // a meme nobody commented on, or a redelivered MEME_DELETED whose cascade already
                // ran: an empty COMMENTS_DELETED states no fact a consumer could act on, and the
                // idempotent cascade would re-emit it on every redelivery — noise on a shared topic
                return;
            }
            commentEvents.commentsDeleted(memeId, dropped);
        }
    }
}
