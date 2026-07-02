package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.MemeDirectory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Optional;
import java.util.Set;

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
    public static final String EXISTING_MEME = "known-meme";

    @Bean
    @Primary
    SecurityAuthenticationGate stubSecurityAuthenticationGate() {
        return token -> VALID_TOKEN.equals(token) ? Optional.of(SIGNED_IN_USER)
                : SECOND_TOKEN.equals(token) ? Optional.of(SECOND_USER)
                : Optional.empty();
    }

    @Bean
    @Primary
    MemeDirectory stubMemeDirectory() {
        return Set.of(EXISTING_MEME)::contains;
    }
}
