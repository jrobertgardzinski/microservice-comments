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
        kafka.send(KafkaTracing.withCid("comments-events", email, mapper.writeValueAsString(mapper.createObjectNode()
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
        try {
            return Optional.of(PurgeRule.parse(rule.asText()));
        } catch (IllegalArgumentException invalid) {
            LOG.warn("ignoring invalid comments purge rule ({}), using the default", invalid.getMessage());
            return Optional.empty();
        }
    }
}
