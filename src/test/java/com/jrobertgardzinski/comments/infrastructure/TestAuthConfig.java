package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.comments.application.DeleteThread;
import com.jrobertgardzinski.comments.application.MemeDirectory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Test doubles for the two outbound dependencies: the security gate knows two users by their
 * well-known tokens, and the meme directory says one meme exists. The real HTTP adapters run in
 * the compose smoke test.
 */
@TestConfiguration
public class TestAuthConfig {

    public static final String VALID_TOKEN = "test-token";
    public static final String SIGNED_IN_USER = "alice@example.com";
    public static final String SECOND_TOKEN = "test-token-bob";
    public static final String SECOND_USER = "bob@example.com";
    public static final String MODERATOR_TOKEN = "test-token-mod";
    public static final String MODERATOR_USER = "mod@example.com";
    public static final String EXISTING_MEME = "known-meme";

    @Bean
    @Primary
    SecurityAuthenticationGate stubSecurityAuthenticationGate() {
        return token -> switch (token == null ? "" : token) {
            case VALID_TOKEN -> Optional.of(new Caller(SIGNED_IN_USER, Set.of("USER")));
            case SECOND_TOKEN -> Optional.of(new Caller(SECOND_USER, Set.of("USER")));
            case MODERATOR_TOKEN -> Optional.of(new Caller(MODERATOR_USER, Set.of("USER", "MODERATOR")));
            default -> Optional.empty();
        };
    }

    @Bean
    @Primary
    MemeDirectory stubMemeDirectory() {
        return Set.of(EXISTING_MEME)::contains;
    }

    /** The MEME_DELETED cascade, minus the broker: scenarios hand the listener a payload
     *  directly (Kafka listeners are disabled in tests, so the real bean is absent). */
    @Bean
    public Consumer<String> memesEventsAnnouncer(DeleteThread deleteThread, ObjectMapper mapper) {
        MemesEventsListener listener = new MemesEventsListener(deleteThread, mapper);
        return payload -> listener.receive(payload, null);   // no cid on the direct, broker-less path
    }
}
