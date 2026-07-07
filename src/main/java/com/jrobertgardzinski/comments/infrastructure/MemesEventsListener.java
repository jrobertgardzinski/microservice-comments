package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.comments.application.DeleteThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * The cascade behind meme deletions: microservice-memes announces MEME_DELETED on
 * {@code memes-events}, and this service drops the meme's whole comment thread — eventually
 * consistent, idempotent.
 */
@Component
@ConditionalOnProperty(name = "comments.kafka-enabled", havingValue = "true")
class MemesEventsListener {

    private static final Logger LOG = LoggerFactory.getLogger(MemesEventsListener.class);

    private final DeleteThread deleteThread;
    private final ObjectMapper mapper;

    MemesEventsListener(DeleteThread deleteThread, ObjectMapper mapper) {
        this.deleteThread = deleteThread;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "memes-events", groupId = "comments")
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
            LOG.warn("dropping malformed memes event: {}", payload);
            return;
        }
        if ("MEME_DELETED".equals(event.path("type").asText())) {
            String memeId = event.path("memeId").asText();
            deleteThread.execute(memeId);
            LOG.info("dropped the comment thread of deleted meme {}", memeId);
        }
    }
}
