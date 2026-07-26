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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The comments service's side of the account-deletion saga: a PURGE_USER_CONTENT command purges
 * the leaver's comments (per this service's axis of the policy) and the confirmation goes back on
 * {@code comments-events}. Idempotent, so at-least-once delivery needs no extra dedup.
 */
@Component
@ConditionalOnProperty(name = "comments.kafka-enabled", havingValue = "true")
class PurgeCommandsListener {

    private static final Logger LOG = LoggerFactory.getLogger(PurgeCommandsListener.class);

    private final PurgeUserComments purgeUserComments;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    PurgeCommandsListener(PurgeUserComments purgeUserComments, KafkaTemplate<String, String> kafka,
                          ObjectMapper mapper) {
        this.purgeUserComments = purgeUserComments;
        this.kafka = kafka;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "content-commands", groupId = "comments")
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
        purgeUserComments.execute(email, requestedRule(command));
        // the saga id identifies the run in logs; the e-mail is PII and stays out of INFO lines
        LOG.info("purged the comments of one leaver (saga {})", sagaId);
        // forward the cid on the confirmation so security's listener continues the same trace
        // the same topic the cascade's COMMENTS_DELETED rides (constant shared so the two
        // producers cannot drift apart); consumers tell the traffic apart by "type"
        kafka.send(KafkaTracing.withCid(KafkaCommentEvents.TOPIC, email, mapper.writeValueAsString(mapper.createObjectNode()
                        .put("type", "USER_CONTENT_PURGED")
                        .put("sagaId", sagaId)
                        .put("email", email)
                        // envelope version (workspace ADR 0004): fields only ever added within version 1
                        .put("version", 1))))
                // fire-and-forget hides broker trouble; at least leave a trace when the send fails
                // (the orchestrator's timeout will retry the command — the purge is idempotent)
                .whenComplete((confirmation, failure) -> {
                    if (failure != null) {
                        LOG.error("failed to send the USER_CONTENT_PURGED confirmation (saga {})",
                                sagaId, failure);
                    }
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
