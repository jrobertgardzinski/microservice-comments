package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.comments.application.MarkUserCommentsForErasure;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import com.jrobertgardzinski.comments.application.RestoreUserComments;
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
 * The comments service's side of the account-deletion saga — a participant in TWO phases, which is
 * what makes the whole saga compensatable.
 *
 * <ul>
 *   <li>{@code PURGE_USER_CONTENT} — the reversible step: the leaver's comments are MARKED
 *       ({@link MarkUserCommentsForErasure}), which takes them out of every thread and deletes
 *       nothing, and the confirmation goes back on {@code comments-events} exactly as before. The
 *       name on the wire is unchanged on purpose — the orchestrator's contract is "make this
 *       leaver's content go away and tell me when", and the pacts that pin the envelope stay
 *       green.</li>
 *   <li>{@code ERASE_USER_CONTENT} — the closure: everybody confirmed, the case cannot fail any
 *       more, and {@link PurgeUserComments} applies the rule for real.</li>
 *   <li>{@code RESTORE_USER_CONTENT} — the compensation: a sibling participant failed, so the marks
 *       come off ({@link RestoreUserComments}) and the conversation is whole again.</li>
 * </ul>
 *
 * <p>All three are idempotent, so at-least-once delivery needs no extra dedup. Only the first is
 * confirmed: the other two are the orchestrator ENDING the case, and answering them would tell it
 * something it has already decided. What guards them is retrying ({@link SagaRetryBudget}) and, for
 * a closure lost beyond that budget, {@link StuckErasureWatch} — the backlog of marks nobody closed
 * is visible and alarmed on, never swept on a timer.
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
 * <p>That last one is why this class opens a transaction: only here are the mark and its
 * confirmation one unit of work. Either the leaver's comments are reserved AND the outbox owes the
 * orchestrator a confirmation, or neither happened and the command comes back. The use case's own
 * transactional decorator joins this one (Spring's default propagation), exactly as
 * {@link MemesEventsListener} does for the cascade hop next door.
 */
@Component
@ConditionalOnProperty(name = "comments.kafka-enabled", havingValue = "true")
class PurgeCommandsListener {

    private static final Logger LOG = LoggerFactory.getLogger(PurgeCommandsListener.class);

    /** The reversible mark; its confirmation is what the orchestrator's quorum counts. */
    static final String MARK = "PURGE_USER_CONTENT";
    /** The closure: the orchestrator says the case is settled, so the rule may be applied. */
    static final String ERASE = "ERASE_USER_CONTENT";
    /** The compensation: the marks come off and the comments are back in their threads. */
    static final String RESTORE = "RESTORE_USER_CONTENT";

    private final MarkUserCommentsForErasure markForErasure;
    private final RestoreUserComments restoreUserComments;
    private final PurgeUserComments purgeUserComments;
    private final PurgeConfirmations confirmations;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    PurgeCommandsListener(MarkUserCommentsForErasure markForErasure,
                          RestoreUserComments restoreUserComments,
                          PurgeUserComments purgeUserComments, PurgeConfirmations confirmations,
                          ObjectMapper mapper, TransactionTemplate tx) {
        this.markForErasure = markForErasure;
        this.restoreUserComments = restoreUserComments;
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
        String type = command.path("type").asText();
        if (!MARK.equals(type) && !ERASE.equals(type) && !RESTORE.equals(type)) {
            return;
        }
        String sagaId = command.path("sagaId").asText();
        String email = command.path("email").asText();
        if (email.isBlank()) {
            // no email, nothing to act on and no key to confirm under — drop WITHOUT confirming,
            // so the orchestrator's timeout (not a hollow success) surfaces the broken command
            LOG.warn("dropping {} without an email (saga {})", type, sagaId);
            return;
        }
        switch (type) {
            case MARK -> {
                markAndConfirm(sagaId, email);
                // the saga id identifies the run in logs; the e-mail is PII and stays out of INFO
                LOG.info("marked one leaver's comments for erasure (saga {})", sagaId);
            }
            case ERASE -> {
                // the rule is parsed OUTSIDE the transaction: it is pure, and its WARN about an
                // unreadable rule must not be re-logged on every retry of the same command. It
                // rides the CLOSURE, because that is where the rule is finally applied
                Optional<PurgeRule> rule = requestedRule(command);
                tx.executeWithoutResult(status -> purgeUserComments.execute(email, rule));
                LOG.info("erased one leaver's marked comments on the saga's closure (saga {})", sagaId);
            }
            case RESTORE -> {
                tx.executeWithoutResult(status -> restoreUserComments.execute(email));
                LOG.info("restored one leaver's comments: the saga compensated (saga {})", sagaId);
            }
            default -> throw new IllegalStateException("unreachable: " + type);
        }
    }

    /**
     * The mark and the promise to report it, as ONE transaction. A failure anywhere inside
     * propagates out of {@link #receive}, which is what makes the error handler retry the record with
     * backoff and — the mark being idempotent — run the whole thing again.
     *
     * <p>The confirmation is announced INSIDE, not after: announcing after the commit would leave a
     * window where the comments are hidden and nothing owes the orchestrator a word about it, which
     * is the failure mode that ends with the leaver holding a restored account whose content nobody
     * can see. The outbox library parks the actual send on the commit, so nothing is published
     * before the mark is real. The topic is the cascade's own ({@link KafkaCommentEvents#TOPIC}, one
     * constant for both producers); consumers tell the two conversations apart by {@code type}.
     */
    private void markAndConfirm(String sagaId, String email) {
        tx.executeWithoutResult(status -> {
            markForErasure.execute(email);
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
