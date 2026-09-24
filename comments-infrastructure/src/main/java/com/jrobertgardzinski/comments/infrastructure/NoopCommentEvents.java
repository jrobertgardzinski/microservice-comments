package com.jrobertgardzinski.comments.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Broker-less stand-in (tests, bare dev runs on H2): a dropped thread is announced to nobody.
 *
 * <p>Its job is to keep the {@link CommentEvents} contract TOTAL — exactly one implementation in
 * every environment — so a {@link MemesEventsListener} wired by hand always has something to talk
 * to. The cucumber harness does exactly that, the Kafka-driven beans being absent in tests. The
 * twin of microservice-memes' {@code NoopMemeEvents}.
 */
@Component
@ConditionalOnProperty(name = "comments.kafka-enabled", havingValue = "false", matchIfMissing = true)
class NoopCommentEvents implements CommentEvents {

    @Override
    public void commentsDeleted(String memeId, List<String> commentIds) {
        // nothing to tell — no other service is listening in this environment
    }
}
