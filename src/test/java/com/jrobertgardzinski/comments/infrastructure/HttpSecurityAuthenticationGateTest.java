package com.jrobertgardzinski.comments.infrastructure;

import com.sun.net.httpserver.HttpServer;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The introspecting gate is the DEFAULT one ({@code matchIfMissing = true}), so whatever it decides
 * about privilege is what a start that forgets {@code SECURITY_VERIFY} enforces. It used to read
 * {@code roles} from {@code GET /me} and ignore {@code mfaCompliant} beside it, while the offline
 * twin and microservice-memes both applied {@link Caller#withMfaFloor}: an under-enrolled MODERATOR
 * was a plain USER against the gallery and a full moderator against the comment threads, able to
 * hide and delete other people's words. A stub {@code /me} is the only place that difference shows:
 * {@link JwtSecurityAuthenticationGateTest} already pins the RULE through the offline gate, and this
 * pins that the introspecting one asks it at all.
 */
@Epic("Infrastructure")
@Feature("Authentication gate")
@Story("MFA floor")
class HttpSecurityAuthenticationGateTest {

    private HttpServer security;
    private String body = "";

    @BeforeEach
    void startStubSecurity() throws IOException {
        security = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        security.createContext("/me", exchange -> {
            byte[] answer = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
        });
        security.start();
    }

    @AfterEach
    void stopStubSecurity() {
        security.stop(0);
    }

    @Test
    @DisplayName("an under-enrolled moderator is served as a plain USER")
    void the_floor_is_applied_to_an_introspected_caller() {
        body = "{\"email\":\"mod@example.com\",\"roles\":[\"USER\",\"MODERATOR\"],\"mfaCompliant\":false}";

        Optional<Caller> caller = gate().callerFor("any-token");

        assertTrue(caller.isPresent(), "the account still signs in — only privilege is withheld");
        assertFalse(caller.get().isModerator(),
                "the MFA floor: MODERATOR is withheld until security says mfaCompliant");
        assertTrue(caller.get().roles().contains("USER"));
    }

    @Test
    @DisplayName("a compliant moderator keeps every role")
    void a_compliant_moderator_is_let_through() {
        body = "{\"email\":\"mod@example.com\",\"roles\":[\"USER\",\"MODERATOR\"],\"mfaCompliant\":true}";

        Optional<Caller> caller = gate().callerFor("any-token");

        assertTrue(caller.isPresent());
        assertEquals("mod@example.com", caller.get().email());
        assertTrue(caller.get().isModerator());
    }

    @Test
    @DisplayName("a security that does not report the flag withholds privilege rather than granting it")
    void a_missing_flag_fails_closed() {
        body = "{\"email\":\"mod@example.com\",\"roles\":[\"USER\",\"MODERATOR\"]}";

        assertFalse(gate().callerFor("any-token").orElseThrow().isModerator(),
                "an absent mfaCompliant is not a statement of compliance");
    }

    private HttpSecurityAuthenticationGate gate() {
        return new HttpSecurityAuthenticationGate("http://127.0.0.1:" + security.getAddress().getPort());
    }
}
