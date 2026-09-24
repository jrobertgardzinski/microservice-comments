package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.comments.application.RekeyUserComments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What this service does about somebody changing their e-mail address: it re-keys their rows onto
 * the new one ({@link RekeyUserComments}).
 *
 * <p>The address is not an identifier here, it is a NAME that identity happens to use as one — the
 * author column holds whatever the token said when the comment was written. Until this listener
 * existed, a confirmed address change stranded a person's whole history: their comments stopped
 * being theirs, their votes stopped counting for them, and an account deletion marked nothing while
 * confirming an erasure, which left the words in the thread for the next registrant of the freed
 * address to inherit.
 *
 * <p>{@code security-events} is the facts topic, so most of what arrives on it is somebody else's
 * business — {@code ACCOUNT_DELETION_REQUESTED} belongs to microservice-offboarding, which turns it
 * into the purge commands {@link PurgeCommandsListener} answers. Everything but
 * {@code EMAIL_CHANGED} is ignored WITHOUT a word: a fact this service has no use for is not an
 * anomaly, and a log line per deletion request in the portal would be noise that teaches an operator
 * to stop reading.
 *
 * <p>The wiring is deliberately the other two listeners', down to the group id: one consumer group
 * per service ({@code comments}), a container id spelled out so {@link SagaListenersHealth} can name
 * it under {@code /actuator/health} — with three loops in this service, "which one stopped" has to be
 * answerable — {@code auto-offset-reset=earliest} from {@code application.properties} (a group with
 * no committed offset must read the renames that were announced before it existed, not skip them),
 * and the same error handler, retry budget and heartbeat that {@link SagaParticipantConfig} installs
 * on every container. None of that had to be asked for; all of it would have been missing had this
 * listener been wired anywhere else, and a listener the health lamp does not know about is a listener
 * nobody notices dying.
 *
 * <p>One transaction per record, because the re-key touches two tables and half a person is worse
 * than none of them. A failure propagates out of {@link #receive} and the record is retried with
 * backoff — which is safe precisely because the re-key is idempotent (see {@link RekeyUserComments}).
 */
@Component
@ConditionalOnProperty(name = "comments.kafka-enabled", havingValue = "true")
class SecurityEventsListener {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityEventsListener.class);

    /** The one fact on this topic this service acts on. */
    static final String EMAIL_CHANGED = "EMAIL_CHANGED";

    private final RekeyUserComments rekeyUserComments;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    SecurityEventsListener(RekeyUserComments rekeyUserComments, ObjectMapper mapper,
                           TransactionTemplate tx) {
        this.rekeyUserComments = rekeyUserComments;
        this.mapper = mapper;
        this.tx = tx;
    }

    @KafkaListener(id = "comments-security-events", topics = "security-events", groupId = "comments")
    void receive(String payload,
                 @Header(name = KafkaTracing.HEADER, required = false) String cid) throws Exception {
        if (cid != null) {
            MDC.put("cid", cid);   // continue the trace the address change started in security
        }
        try {
            handle(payload);
        } finally {
            MDC.remove("cid");
        }
    }

    private void handle(String payload) throws Exception {
        JsonNode fact;
        try {
            fact = mapper.readTree(payload);
        } catch (Exception malformed) {
            // NOT the payload itself: every fact on this topic carries somebody's address, and a
            // malformed one may — the same rule the other two listeners follow, for the same reason
            LOG.warn("dropping a malformed security fact ({} chars, not valid JSON)",
                    payload == null ? 0 : payload.length());
            return;
        }
        if (!EMAIL_CHANGED.equals(fact.path("type").asText())) {
            return;
        }
        String oldEmail = fact.path("oldEmail").asText();
        String newEmail = fact.path("email").asText();
        if (oldEmail.isBlank() || newEmail.isBlank()) {
            // a rename missing either end cannot be carried out and cannot be retried into
            // existence; the id is derived from the two addresses, so it is what an investigation
            // has to go on
            LOG.warn("dropping an {} without both addresses (fact {})", EMAIL_CHANGED, factId(fact));
            return;
        }
        int moved = tx.execute(status -> rekeyUserComments.execute(oldEmail, newEmail));
        // the fact id, never the addresses: this line reports a person's identity changing and the
        // log has no retention anybody here controls. The id is derived from the pair, so the same
        // rename always reads the same — which is also what makes "0 moved" recognisable as the
        // redelivery it usually is
        LOG.info("re-keyed {} rows onto a member's new address (fact {})", moved, factId(fact));
    }

    private static String factId(JsonNode fact) {
        String id = fact.path("id").asText();
        return id.isBlank() ? "no id" : id;
    }
}
