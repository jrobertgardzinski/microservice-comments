package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jrobertgardzinski.outbox.OutboxEvent;
import com.jrobertgardzinski.outbox.spring.SpringOutbox;
import org.slf4j.MDC;

import java.util.List;

/**
 * The second hop of the meme-deletion CASCADE, on {@code comments-events}: memes announced
 * MEME_DELETED, this service dropped the thread, and now it names the comments that went so
 * microservice-user-collections can drop whatever it had saved of them. Nobody orchestrates this —
 * each service reacts to the hop before it and announces its own.
 *
 * <p><strong>Why there IS an outbox here now, having argued there should not be.</strong> The old
 * argument was about price, not about value, and it was honest at the time: an outbox bought the
 * guarantee that a committed change's announcement eventually reaches the broker, and the price was
 * a table, a republisher, a retention sweep, a migration and the tests for all four — written from
 * scratch, in this service. Against the stake here (one stale collection entry pointing at a comment
 * that no longer exists, which the UI's read-repair must survive anyway because the event is
 * asynchronous) that price was not worth paying.
 *
 * <p>Round 10 changed the price, not the stake. The mechanism became a kernel library
 * ({@code com.jrobertgardzinski:transactional-outbox} plus its Spring adapter), extracted from
 * microservice-memes' implementation after three rounds of hardening, with its own tests. What this
 * service now pays is one migration and one configuration class — and at THAT price, accepting a
 * permanently lost event stops being a trade and starts being a leftover. It mattered that the loss
 * was unrepairable: a redelivered MEME_DELETED finds an empty thread and deliberately announces
 * nothing, so nothing in the system ever re-derives a lost COMMENTS_DELETED. The audit of 26.07
 * named the deeper problem — four copies of the participant role, each with different guarantees —
 * and one library with one guarantee is the answer to it.
 *
 * <p>What the guarantee is now, concretely: the announcement row is written in the SAME transaction
 * as the thread delete (opened by {@link MemesEventsListener}, joined by {@code DeleteThread}'s
 * decorator), so a rollback still lets nothing out — but a crash or a broker outage between that
 * commit and the send no longer loses anything either, because the row stays unpublished and the
 * library's republisher re-sends it and marks it only once the broker has confirmed.
 *
 * <p>Unchanged from round 9, deliberately: the topic, the envelope (version 1 — fields are only ever
 * added within a version, workspace ADR 0004), the partition key, and what the event does NOT carry.
 * It names comment ids and a meme id, no authors — a topic whose other traffic is PII-bearing (the
 * saga's confirmations name the leaver) gains no new PII.
 */
class KafkaCommentEvents implements CommentEvents {

    /**
     * Shared with the saga's confirmations on purpose — one topic per producing service, types
     * tell the traffic apart (offboarding's router keys on {@code type}, so it never sees these).
     * Pinned here so both producers in this service spell it the same way.
     */
    static final String TOPIC = "comments-events";

    static final String COMMENTS_DELETED = "COMMENTS_DELETED";

    private final SpringOutbox outbox;
    private final ObjectMapper mapper;

    KafkaCommentEvents(SpringOutbox outbox, ObjectMapper mapper) {
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Override
    public void commentsDeleted(String memeId, List<String> commentIds) {
        outbox.announce(announcementOf(memeId, commentIds));
    }

    /**
     * The event as it will be stored AND as it will be sent — the row is the record.
     *
     * <p>The envelope id is minted by the library BEFORE the payload and pasted INTO it. Round 9's
     * comment on that field said "random, not derived: there is no republication path to deduplicate
     * against"; there is one now, which is exactly why the id may no longer be an incidental
     * {@code UUID.randomUUID()} invented while serialising. A confirmed send whose {@code published}
     * mark did not land IS re-sent, so a consumer has to be able to tell the duplicate from a second
     * deletion — and it can only do that if the row's key and the payload's {@code id} are the same
     * string, for the first attempt and for every redelivery of that row. The library keeps the
     * payload opaque (never parsed, never re-serialised, so a redelivery is byte-identical by
     * construction), which is precisely why it cannot paste the id in itself.
     */
    OutboxEvent announcementOf(String memeId, List<String> commentIds) {
        String eventId = OutboxEvent.newId();
        ObjectNode event = mapper.createObjectNode()
                .put("id", eventId)
                .put("type", COMMENTS_DELETED)
                .put("memeId", memeId);
        ArrayNode ids = event.putArray("commentIds");
        commentIds.forEach(ids::add);
        // envelope version (workspace ADR 0004): fields only ever added within version 1
        event.put("version", 1);

        String payload;
        try {
            payload = mapper.writeValueAsString(event);
        } catch (Exception impossible) {
            // Ids and a meme id only — if THIS cannot be serialised, nothing in this service can.
            // It is a throw and no longer a logged return, and the change is deliberate: the
            // announcement is now part of the thread delete's unit of work, so failing to build it
            // must take that transaction down rather than commit a teardown nobody will hear about.
            // The payload itself never reaches the log — whatever went wrong, the ids are not it.
            throw new IllegalStateException("could not serialise the " + COMMENTS_DELETED
                    + " announcement for a thread of " + commentIds.size() + " comment(s)", impossible);
        }
        // keyed by memeId, like MEME_DELETED itself: the whole cascade of one meme stays on one
        // partition, so a consumer never sees the comments' fate before the meme's
        // the cid is read from the MDC HERE, at announce time — while the listener that started
        // this cascade hop still has it — and stored in the row, because the send may happen again
        // hours later on a scheduler thread with no trace context at all — which is why reading the
        // MDC at SEND time (what the saga's confirmations did until round 11, when they joined this
        // outbox) is not an option for anything the republisher may re-send.
        return new OutboxEvent(eventId, TOPIC, COMMENTS_DELETED, memeId,
                MDC.get(CorrelationIdFilter.MDC_KEY), payload);
    }
}
