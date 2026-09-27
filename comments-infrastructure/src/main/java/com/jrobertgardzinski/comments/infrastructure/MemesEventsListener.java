package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.comments.application.CommentEvents;
import com.jrobertgardzinski.comments.application.DeleteThread;
import com.jrobertgardzinski.comments.deletion.CommentsDeletionParticipant;
import com.jrobertgardzinski.deletion.DeletionMessages;
import com.jrobertgardzinski.deletion.MemeDeleted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The wire end of the cascade's first hop: microservice-memes announces MEME_DELETED on
 * {@code memes-events}, and this listener carries it to
 * {@link CommentsDeletionParticipant}, which decides what happens to the thread.
 *
 * <p>What is left here is transport and nothing else — the correlation id, the JSON, the
 * "malformed is dropped" rule, and the unit of work the participant runs in. Everything the hop
 * DECIDES moved to the participant when the cascade became a protocol: that an empty thread is
 * announced to nobody, that a deletion naming no meme is dropped, that the announcement shares
 * the drop's transaction. Those are promises the portal's one-process specs prove against the
 * library's contract, and they were untestable while they lived in a Kafka listener.
 *
 * <p>The transaction is still opened HERE, because a {@code TransactionTemplate} is Spring's and
 * the participant is not Spring's; it is handed over as the {@code UnitOfWork} the contract
 * requires. A failure anywhere inside propagates out of {@code receive}, which is what makes
 * Kafka redeliver the MEME_DELETED so the whole (idempotent) hop runs again.
 */
@Component
@ConditionalOnProperty(name = "comments.kafka-enabled", havingValue = "true")
class MemesEventsListener {

    private static final Logger LOG = LoggerFactory.getLogger(MemesEventsListener.class);

    private final CommentsDeletionParticipant participant;
    private final ObjectMapper mapper;

    MemesEventsListener(DeleteThread deleteThread, CommentEvents commentEvents, ObjectMapper mapper,
                        TransactionTemplate tx) {
        this.mapper = mapper;
        this.participant = new CommentsDeletionParticipant(deleteThread, commentEvents,
                step -> tx.executeWithoutResult(status -> step.run()));
    }

    /**
     * The container id is spelled out (the group id is unchanged): it is what
     * {@link SagaListenersHealth} prints under {@code /actuator/health}, and with two listeners in this
     * service the lamp has to be able to say WHICH loop stopped — a stalled cascade leaves deleted
     * memes with their comment threads, a stalled saga listener stops account deletions closing.
     */
    @KafkaListener(id = "comments-memes-events", topics = "memes-events", groupId = "comments")
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
        if (!DeletionMessages.MEME_DELETED.equals(event.path(DeletionMessages.Field.TYPE).asText())) {
            // memes-events carries the rest of a meme's life too; not ours, not worth a line
            return;
        }
        MemeDeleted.of(event.path(DeletionMessages.Field.MEME_ID).asText())
                .ifPresentOrElse(participant::handle,
                        () -> LOG.warn("dropping a MEME_DELETED whose memeId is missing or is"
                                + " not an id"));
    }
}
