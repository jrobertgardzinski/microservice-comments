package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The second hop of the meme-deletion CASCADE, on {@code comments-events}: memes announced
 * MEME_DELETED, this service dropped the thread, and now it names the comments that went so
 * microservice-user-collections can drop whatever it had saved of them. Nobody orchestrates this —
 * each service reacts to the hop before it and announces its own.
 *
 * <p><strong>Why no outbox here, when the saga has one.</strong> An outbox buys one thing: the
 * guarantee that an event whose transaction committed WILL eventually reach the broker. That
 * guarantee is worth its price (a table, a republisher, retention, a migration) exactly where a
 * lost event costs something a retry cannot fix. It does in the saga: a lost USER_CONTENT_PURGED
 * leaves the orchestrator waiting for a confirmation that will never come, so it retries, then
 * compensates, then announces PORTAL_PURGE_FAILED — a false failure on a GDPR obligation, over an
 * erasure that actually happened. There, delivery IS the contract.
 *
 * <p>Here the whole stake is one stale row: a collection entry pointing at a comment that no longer
 * exists. Nothing waits for this event, nothing times out on it, nothing compensates. The UI's
 * read-repair (it must handle a dangling reference anyway — the event is asynchronous, so there is
 * always a window where the row is stale) drops what it cannot resolve. Buying durability machinery
 * to protect a dead row would be paying saga prices for cascade stakes; the choreography's premise
 * is precisely that the worst outcome of a failure is that dead row.
 *
 * <p>What is NOT optional is the ordering: a rollback must never let an announcement out. That
 * costs nothing to get right — the transaction lives inside {@code DeleteThread}'s decorator, so a
 * rollback throws and {@link MemesEventsListener} never reaches the announcement. The send is
 * therefore always after a successful commit, and its failure is only ever logged.
 *
 * <p>Also note what the event does NOT carry: comment ids and a meme id, no authors — a topic
 * whose other traffic is PII-bearing (the saga's confirmations name the leaver) gains no new PII.
 */
@Component
@ConditionalOnProperty(name = "comments.kafka-enabled", havingValue = "true")
class KafkaCommentEvents implements CommentEvents {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaCommentEvents.class);

    /**
     * Shared with the saga's confirmations on purpose — one topic per producing service, types
     * tell the traffic apart (offboarding's router keys on {@code type}, so it never sees these).
     * Pinned here so both producers in this service spell it the same way.
     */
    static final String TOPIC = "comments-events";

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    KafkaCommentEvents(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.kafka = kafka;
        this.mapper = mapper;
    }

    @Override
    public void commentsDeleted(String memeId, List<String> commentIds) {
        ObjectNode event = mapper.createObjectNode()
                // random, not derived: there is no republication path to deduplicate against —
                // a redelivered MEME_DELETED finds an empty thread and announces nothing at all
                .put("id", UUID.randomUUID().toString())
                .put("type", "COMMENTS_DELETED")
                .put("memeId", memeId);
        ArrayNode ids = event.putArray("commentIds");
        commentIds.forEach(ids::add);
        // envelope version (workspace ADR 0004): fields only ever added within version 1
        event.put("version", 1);

        String payload;
        try {
            payload = mapper.writeValueAsString(event);
        } catch (Exception impossible) {
            // ids and a meme id only — if THIS cannot be serialised, nothing in this service can
            LOG.error("could not serialise the COMMENTS_DELETED announcement for meme {}", memeId,
                    impossible);
            return;
        }
        // keyed by memeId, like MEME_DELETED itself: the whole cascade of one meme stays on one
        // partition, so a consumer never sees the comments' fate before the meme's
        kafka.send(KafkaTracing.withCid(TOPIC, memeId, payload))
                // fire-and-forget: the thread is already gone and no retry of ours can change
                // that, so a failed send costs the stale rows described above — worth a loud line
                // in the log, not a rollback and not an outbox
                .whenComplete((sent, failure) -> {
                    if (failure != null) {
                        LOG.error("failed to announce COMMENTS_DELETED for meme {} ({} comment(s));"
                                        + " collections may keep stale references until read-repair",
                                memeId, commentIds.size(), failure);
                    }
                });
    }
}
