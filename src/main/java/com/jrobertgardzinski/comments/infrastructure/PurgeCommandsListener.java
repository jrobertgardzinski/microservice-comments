package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import com.jrobertgardzinski.comments.config.PurgeRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The comments service's side of the account-deletion saga: a PURGE_USER_CONTENT command (the command
 * microservice-offboarding publishes) purges the leaver's comments (per this service's axis of the
 * policy) and the confirmation goes back on {@code comments-events}. Idempotent, so at-least-once
 * delivery needs no extra dedup.
 *
 * <p><strong>The three guarantees this participant used to lack</strong> (the 26.07 audit's second
 * theme: one role, four implementations, four sets of promises). The reasoning for each lives where it
 * is enforced:
 * <ul>
 *   <li>a command is no longer lost to a one-second hiccup — {@link SagaRetryBudget} retries it with
 *       backoff for a budget derived from the orchestrator's own timeline, then drops it loudly and
 *       counted instead of silently in a millisecond;</li>
 *   <li>a stalled listener loop no longer hides behind a green process — {@link SagaListenersHealth}
 *       turns readiness red;</li>
 *   <li>the confirmation is no longer fire-and-forget with an ERROR log for consolation —
 *       {@link PurgeConfirmations} writes it into the outbox round 10 already built, in the SAME
 *       transaction as the purge it confirms.</li>
 * </ul>
 *
 * <p>That last one is why this class opens a transaction: only here are the erasure and its
 * confirmation one unit of work. Either the leaver's comments are dealt with AND the outbox owes the
 * orchestrator a confirmation, or neither happened and the command comes back. The use case's own
 * transactional decorator joins this one (Spring's default propagation), exactly as
 * {@link MemesEventsListener} does for the cascade hop next door.
 */
@Component
@ConditionalOnProperty(name = "comments.kafka-enabled", havingValue = "true")
class PurgeCommandsListener {

    private static final Logger LOG = LoggerFactory.getLogger(PurgeCommandsListener.class);

    private final PurgeUserComments purgeUserComments;
    private final PurgeConfirmations confirmations;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    PurgeCommandsListener(PurgeUserComments purgeUserComments, PurgeConfirmations confirmations,
                          ObjectMapper mapper, TransactionTemplate tx) {
        this.purgeUserComments = purgeUserComments;
        this.confirmations = confirmations;
        this.mapper = mapper;
        this.tx = tx;
    }

    /**
     * The container id is spelled out (the group id is unchanged, {@code comments} — with a group id
     * present, the id names only the container): it is what {@link SagaListenersHealth} prints under
     * {@code /actuator/health}, and this service has TWO listeners, so "which one stopped" has to be
     * answerable.
     */
    @KafkaListener(id = "comments-purge-commands", topics = "content-commands", groupId = "comments")
    void receive(String payload,
                 @Header(name = KafkaTracing.HEADER, required = false) String cid) throws Exception {
        if (cid != null) {
            MDC.put("cid", cid);   // continue the trace the deletion request started in security
        }
        try {
            handle(payload);
        } finally {
            MDC.remove("cid");
        }
    }

    private void handle(String payload) throws Exception {
        JsonNode command;
        try {
            command = mapper.readTree(payload);
        } catch (Exception malformed) {
            // NOT the payload itself: a purge command carries the leaver's e-mail, and even a
            // malformed one may — PII stays out of the logs, the size is enough to investigate
            LOG.warn("dropping a malformed command ({} chars, not valid JSON)",
                    payload == null ? 0 : payload.length());
            return;
        }
        if (!"PURGE_USER_CONTENT".equals(command.path("type").asText())) {
            return;
        }
        String sagaId = command.path("sagaId").asText();
        String email = command.path("email").asText();
        if (email.isBlank()) {
            // no email, nothing to purge and no key to confirm under — drop WITHOUT confirming,
            // so the orchestrator's timeout (not a hollow success) surfaces the broken command
            LOG.warn("dropping PURGE_USER_CONTENT without an email (saga {})", sagaId);
            return;
        }
        // the rule is parsed OUTSIDE the transaction: it is pure, and its WARN about an unreadable
        // rule must not be re-logged on every retry of the same command
        Optional<PurgeRule> rule = requestedRule(command);
        purgeAndConfirm(sagaId, email, rule);
        // the saga id identifies the run in logs; the e-mail is PII and stays out of INFO lines
        LOG.info("purged the comments of one leaver (saga {})", sagaId);
    }

    /**
     * The erasure and the promise to report it, as ONE transaction. A failure anywhere inside
     * propagates out of {@link #receive}, which is what makes the error handler retry the record with
     * backoff and — the purge being idempotent — run the whole thing again.
     *
     * <p>The confirmation is announced INSIDE, not after: announcing after the commit would leave a
     * window where the comments are dealt with and nothing owes the orchestrator a word about it, which
     * is the failure mode that ends with the leaver holding a restored account whose content is gone.
     * The outbox library parks the actual send on the commit, so nothing is published before the
     * erasure is real. The topic is the cascade's own ({@link KafkaCommentEvents#TOPIC}, one constant
     * for both producers); consumers tell the two conversations apart by {@code type}.
     */
    private void purgeAndConfirm(String sagaId, String email, Optional<PurgeRule> rule) {
        tx.executeWithoutResult(status -> {
            purgeUserComments.execute(email, rule);
            confirmations.confirm(sagaId, email);
        });
    }

    private Optional<PurgeRule> requestedRule(JsonNode command) {
        JsonNode rule = command.path("policy").path("comments");
        if (rule.isMissingNode()) {
            return Optional.empty();
        }
        String text = rule.asText();
        try {
            return Optional.of(PurgeRule.parse(text));
        } catch (IllegalArgumentException invalid) {
            // NOT invalid.getMessage(): parse() pastes the raw rule text from the wire into it —
            // a constant plus the length and a vocabulary-only fragment is enough to investigate
            LOG.warn("ignoring an unparseable comments purge rule ({} chars, looks like '{}'), "
                    + "using the default", text.length(), sanitizedFragment(text));
            return Optional.empty();
        }
    }

    /**
     * The purge-rule VOCABULARY, whole tokens only — never the raw wire text. The old per-character
     * filter ({@code [A-Z_:0-9]}) kept every digit and every uppercase letter, which is exactly the
     * alphabet of phone numbers, PESELs and SHOUTED e-mail addresses — numeric and uppercase PII
     * sailed through it into the WARN. This whitelist inverts the burden of proof: only the three
     * rule words survive, with a popularity threshold (≤4 digits) accepted solely in its grammar
     * position after {@code KEEP_POPULAR_ANONYMIZED:} — a free-standing number is NOT vocabulary,
     * because "601 234 567" is a phone number chunked into innocent-looking ≤4-digit tokens.
     * Everything unrecognised collapses to a single {@code ?} per run, so the log shows the rule's
     * shape ("was it almost a rule?") and none of its content.
     */
    private static final Pattern VOCABULARY = Pattern.compile(
            "(?<![A-Z_0-9:])(?:KEEP_POPULAR_ANONYMIZED(?::\\d{1,4})?|ANONYMIZE_AUTHOR|DELETE)(?![A-Z_0-9:])");

    private static String sanitizedFragment(String text) {
        StringBuilder kept = new StringBuilder();
        Matcher vocabulary = VOCABULARY.matcher(text);
        int consumedUpTo = 0;
        while (vocabulary.find()) {
            if (vocabulary.start() > consumedUpTo) {
                kept.append('?');   // one ? per unrecognised run, no matter how long or what it held
            }
            kept.append(vocabulary.group());
            consumedUpTo = vocabulary.end();
        }
        if (consumedUpTo < text.length() || text.isEmpty()) {
            kept.append('?');
        }
        return kept.length() <= 32 ? kept.toString() : kept.substring(0, 32) + "…";
    }
}
