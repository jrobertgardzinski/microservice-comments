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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * The cascade behind meme deletions: microservice-memes announces MEME_DELETED on
 * {@code memes-events}, and this service drops the meme's whole comment thread — eventually
 * consistent, idempotent.
 *
 * <p>And then it passes the baton on. This class is the seam where the cascade's second hop is
 * decided, because it is the only place that sees BOTH the dropped ids and the whole hop as one
 * unit of work.
 *
 * <p><strong>Round 10 made that unit of work explicit, and that is the point of the change.</strong>
 * Before, the announcement happened AFTER the (decorated) use case returned — outside any
 * transaction — and "publish after commit" was simply the fact that a return meant a commit. That is
 * enough to keep a rollback silent, but it leaves the announcement homeless: the process could die in
 * the gap between the commit and the send, and the event was then gone for good, because a
 * redelivered MEME_DELETED finds an empty thread and deliberately announces nothing.
 *
 * <p>So the hop now opens ONE transaction around both steps. {@code DeleteThread}'s own decorator
 * joins it (Spring's default propagation), and {@link KafkaCommentEvents} writes its outbox row into
 * it — which buys the property the old arrangement could not have: a rollback takes the announcement
 * with it AND a commit makes the announcement durable, whatever happens to the send afterwards. The
 * publication attempt itself is parked on the commit by the outbox library, so it still never runs
 * before the change it announces is real.
 *
 * <p>The transaction is opened HERE rather than inside the use case's decorator for the reason the
 * announcement always lived here: only this class knows whether an announcement is called for at all
 * — an empty thread is announced to nobody — and the announcement is a consequence of the cascade,
 * not of the use case, which knows nothing of brokers.
 */
@Component
@ConditionalOnProperty(name = "comments.kafka-enabled", havingValue = "true")
class MemesEventsListener {

    private static final Logger LOG = LoggerFactory.getLogger(MemesEventsListener.class);

    private final DeleteThread deleteThread;
    private final CommentEvents commentEvents;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    MemesEventsListener(DeleteThread deleteThread, CommentEvents commentEvents, ObjectMapper mapper,
                        TransactionTemplate tx) {
        this.deleteThread = deleteThread;
        this.commentEvents = commentEvents;
        this.mapper = mapper;
        this.tx = tx;
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
            List<String> dropped = dropTheThreadAndAnnounceIt(memeId);
            LOG.info("dropped the comment thread of deleted meme {} ({} comment(s))",
                    memeId, dropped.size());
        }
    }

    /**
     * The hop as ONE transaction: drop the thread and, in the same unit of work, write the
     * announcement the next service needs. Either both land or neither does.
     *
     * <p>A failure anywhere inside propagates out of {@code receive}, which is what makes Kafka
     * redeliver the MEME_DELETED so the whole (idempotent) hop runs again.
     */
    private List<String> dropTheThreadAndAnnounceIt(String memeId) {
        return tx.execute(status -> {
            List<String> dropped = deleteThread.execute(memeId);
            if (dropped.isEmpty()) {
                // a meme nobody commented on, or a redelivered MEME_DELETED whose cascade already
                // ran: an empty COMMENTS_DELETED states no fact a consumer could act on, and the
                // idempotent cascade would re-emit it on every redelivery — noise on a shared topic
                return dropped;
            }
            commentEvents.commentsDeleted(memeId, dropped);
            return dropped;
        });
    }
}
