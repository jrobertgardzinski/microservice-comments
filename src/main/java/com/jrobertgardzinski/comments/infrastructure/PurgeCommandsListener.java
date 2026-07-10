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
            LOG.warn("dropping malformed command: {}", payload);
            return;
        }
        if (!"PURGE_USER_CONTENT".equals(command.path("type").asText())) {
            return;
        }
        String email = command.path("email").asText();
        purgeUserComments.execute(email, requestedRule(command));
        LOG.info("purged comments of {} (saga {})", email, command.path("sagaId").asText());
        // forward the cid on the confirmation so security's listener continues the same trace
        kafka.send(KafkaTracing.withCid("comments-events", email, mapper.writeValueAsString(mapper.createObjectNode()
                .put("type", "USER_CONTENT_PURGED")
                .put("sagaId", command.path("sagaId").asText())
                .put("email", email)
                // envelope version (workspace ADR 0004): fields only ever added within version 1
                .put("version", 1))));
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
