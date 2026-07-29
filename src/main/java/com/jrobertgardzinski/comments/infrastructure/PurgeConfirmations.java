package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.outbox.OutboxEvent;
import com.jrobertgardzinski.outbox.spring.SpringOutbox;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The saga's other half, from this service's side: USER_CONTENT_PURGED, the confirmation
 * microservice-offboarding waits for before it lets microservice-security delete the account.
 *
 * <p><strong>What changed and why it matters.</strong> The confirmation used to be a
 * {@code kafka.send(...)} with a callback that logged a failure — better than microservice-memes'
 * silent version, and still not a guarantee: Spring Kafka commits the command's offset as soon as the
 * listener returns, so "comments purged, offset committed, confirmation lost in the producer's
 * accumulator, ERROR line nobody reads at 3am" was reachable. Its consequence is the worst outcome
 * this system has: the leaver's comments are purged, the orchestrator never hears it, after three
 * re-commands it compensates, and the leaver gets their account back WITHOUT their content, together
 * with a mail apologising that the deletion failed. Meanwhile round 10 had already given this service
 * a transactional outbox, on this very topic, for the cascade's COMMENTS_DELETED.
 *
 * <p>So the confirmation now rides that outbox too — the estimate the audit asked for came out cheap
 * precisely because round 10 paid the price already: one class and one bean, no migration, the same
 * table, the same republisher. It is written in the SAME transaction as the purge (opened by
 * {@link PurgeCommandsListener}, joined by the use case's transactional decorator). That single change
 * buys both directions:
 * <ul>
 *   <li>nothing is purged without a durable obligation to confirm it — if the row cannot be written,
 *       the erasure rolls back with it and the command is retried;</li>
 *   <li>no confirmation escapes a purge that rolled back — the first send is parked on the commit by
 *       the library.</li>
 * </ul>
 * A crash or a broker outage between the commit and the send loses nothing either: the row stays
 * unpublished and the republisher re-sends it, marking it only once the broker has acknowledged.
 *
 * <p><strong>PII, deliberately weighed.</strong> The row carries the leaver's address, because the
 * orchestrator matches confirmations on it (its committed pact says so) — this cannot be a
 * pseudonymised event. It is the one place in this service where an erased address lingers, for at
 * most the outbox retention (24h, {@code COMMENTS_OUTBOX_RETENTION_HOURS}), which is well inside the 30
 * days the orchestrator's own saga table keeps the same address. The logs, which have no retention
 * anyone controls, still never see it.
 *
 * @see KafkaCommentEvents the cascade's announcement on the same topic, same outbox, same discipline
 */
class PurgeConfirmations {

    /** The type the orchestrator's router keys on; the topic is {@link KafkaCommentEvents#TOPIC}. */
    static final String USER_CONTENT_PURGED = "USER_CONTENT_PURGED";

    private final SpringOutbox outbox;
    private final ObjectMapper mapper;

    PurgeConfirmations(SpringOutbox outbox, ObjectMapper mapper) {
        this.outbox = outbox;
        this.mapper = mapper;
    }

    /** Announce the confirmation in the caller's transaction — see the class javadoc. */
    void confirm(String sagaId, String leaver) {
        outbox.announce(confirmationOf(sagaId, leaver));
    }

    /**
     * The confirmation as it will be stored AND as it will be sent — the row is the record.
     *
     * <p>The envelope is unchanged from the bare-send days on purpose: {@code type}, {@code sagaId},
     * {@code email}, {@code version} 1 (workspace ADR 0004 — fields are only ever added within a
     * version), and the orchestrator's committed pact pins exactly these. In particular the event id
     * is NOT pasted into the payload, unlike the cascade's COMMENTS_DELETED: a consumer needs the id
     * to recognise a redelivered duplicate, and for confirmations there is nothing to recognise —
     * recording a
     * participant's confirmation against a saga is idempotent by construction (a set), and a
     * confirmation for a saga that has closed is dropped as stray. Adding a field to a pinned
     * envelope to solve a problem nobody has is churn, not compatibility.
     *
     * <p>Built with Jackson rather than string concatenation, which the sibling announcement can
     * afford: the values here come off the wire (an address, a saga id from a JSON field), and an
     * address may legally contain a quote — {@code "odd\"one"@example.com}. Concatenating that would
     * produce a payload the broker accepts and no consumer can parse.
     *
     * <p>Package-private and free of the outbox so the contract tests can build the real payload
     * without a database — the shape is what they verify, and a table is not part of the shape.
     */
    OutboxEvent confirmationOf(String sagaId, String leaver) {
        String payload;
        try {
            var confirmation = mapper.createObjectNode()
                    .put("type", USER_CONTENT_PURGED)
                    .put("email", leaver)
                    .put("version", 1);
            // A BLANK sagaId is worse than an absent one. The orchestrator drops a confirmation
            // whose sagaId is present but unparseable — a deliberate poison-pill rule — while one
            // with NO sagaId falls back to matching by e-mail. So a purge command that arrived
            // without the field (an older producer, a hand-published record) had its confirmation
            // thrown away rather than matched, and the saga waited out its timeout for an answer
            // that had in fact come back.
            if (sagaId != null && !sagaId.isBlank()) {
                confirmation.put("sagaId", sagaId);
            }
            payload = mapper.writeValueAsString(confirmation);
        } catch (Exception impossible) {
            // Two strings and a number. If THIS cannot be serialised nothing in this service can —
            // and a throw is right: it happens inside the purge's transaction, so the erasure rolls
            // back rather than committing with nobody to tell. The values never reach the message.
            throw new IllegalStateException("could not serialise the " + USER_CONTENT_PURGED
                    + " confirmation of saga " + sagaId, impossible);
        }
        return new OutboxEvent(OutboxEvent.newId(), KafkaCommentEvents.TOPIC, USER_CONTENT_PURGED,
                keyFor(sagaId, leaver), MDC.get(CorrelationIdFilter.MDC_KEY), payload);
    }

    /**
     * The partition key: the saga run, NOT the leaver's address (which is what the bare send used).
     * Two hard reasons and one soft one.
     *
     * <p>The outbox's {@code event_key} column is {@code varchar(64)} and an address may be 254
     * characters (RFC 5321). A long address would fail the INSERT inside the purge's transaction —
     * rolling back the erasure, retry after retry, until the budget dropped the command. The saga id
     * is a UUID: 36 characters, always.
     *
     * <p>A key is not a payload: it shows up in broker tooling, in consumer-lag dashboards and in
     * every dump of the topic, so keeping an address out of it is free hygiene on the one topic whose
     * traffic is about erasing that address.
     *
     * <p>Nothing depends on the old key. The orchestrator's router reads every partition and a
     * confirmation is idempotent; the only property a key must have here is that every redelivery of
     * this row carries the same one, which a stored key does by construction.
     *
     * <p>A command with no {@code sagaId} (the orchestrator always sends one — absence is tolerated
     * only for older producers) falls back to an id DERIVED from the address, the same
     * {@code nameUUIDFromBytes} idiom the orchestrator uses for its own re-published outcomes: never
     * blank, because the outbox refuses a blank key, and never the address itself.
     */
    private static String keyFor(String sagaId, String leaver) {
        return sagaId != null && !sagaId.isBlank()
                ? sagaId
                : UUID.nameUUIDFromBytes(leaver.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
