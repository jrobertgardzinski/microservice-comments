package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The offline gate trusts only what verifies: a token signed by the key the JWKS names is let
 * through with its subject and roles; a forged signature, a foreign issuer, an expired token or a
 * key the JWKS does not know (after one refetch) are all refused — the gate fails closed.
 */
class JwtSecurityAuthenticationGateTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final KeyPair keys = generate();
    private final KeyPair otherKeys = generate();

    @Test
    void a_properly_signed_token_carries_the_caller() throws Exception {
        JwtSecurityAuthenticationGate gate = new JwtSecurityAuthenticationGate(
                () -> Map.of("kid-1", keys.getPublic()), mapper);
        String token = token(keys, "kid-1", "mod@example.com", "[\"USER\",\"MODERATOR\"]",
                Instant.now().getEpochSecond() + 300, "microservice-security");

        Optional<Caller> caller = gate.callerFor(token);

        assertTrue(caller.isPresent());
        assertEquals("mod@example.com", caller.get().email());
        assertTrue(caller.get().isModerator(), "roles ride inside the token");
    }

    @Test
    void an_under_enrolled_moderator_is_served_as_a_plain_user() throws Exception {
        JwtSecurityAuthenticationGate gate = new JwtSecurityAuthenticationGate(
                () -> Map.of("kid-1", keys.getPublic()), mapper);
        String token = token(keys, "kid-1", "mod@example.com", "[\"USER\",\"MODERATOR\"]",
                Instant.now().getEpochSecond() + 300, "microservice-security", false);

        Optional<Caller> caller = gate.callerFor(token);

        assertTrue(caller.isPresent(), "the account still signs in — only privilege is withheld");
        assertTrue(!caller.get().isModerator(),
                "the MFA floor: MODERATOR is withheld until the token says mfaCompliant");
        assertTrue(caller.get().roles().contains("USER"));
    }

    @Test
    void forged_expired_or_foreign_tokens_are_refused() throws Exception {
        JwtSecurityAuthenticationGate gate = new JwtSecurityAuthenticationGate(
                () -> Map.of("kid-1", keys.getPublic()), mapper);
        long alive = Instant.now().getEpochSecond() + 300;

        String forged = token(otherKeys, "kid-1", "x@example.com", "[\"USER\"]", alive, "microservice-security");
        String expired = token(keys, "kid-1", "x@example.com", "[\"USER\"]",
                Instant.now().getEpochSecond() - 1, "microservice-security");
        String foreign = token(keys, "kid-1", "x@example.com", "[\"USER\"]", alive, "someone-else");

        assertTrue(gate.callerFor(forged).isEmpty(), "a signature by another key must not verify");
        assertTrue(gate.callerFor(expired).isEmpty(), "expiry is the offline gate's only revocation");
        assertTrue(gate.callerFor(foreign).isEmpty(), "another issuer's tokens mean nothing here");
        assertTrue(gate.callerFor("not-a-jwt").isEmpty());
    }

    @Test
    void an_unknown_kid_triggers_one_refetch_which_covers_key_rotation() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        JwtSecurityAuthenticationGate gate = new JwtSecurityAuthenticationGate(
                () -> {
                    fetches.incrementAndGet();
                    return Map.of("kid-2", keys.getPublic());
                }, mapper);
        String token = token(keys, "kid-2", "user@example.com", "[\"USER\"]",
                Instant.now().getEpochSecond() + 300, "microservice-security");

        assertTrue(gate.callerFor(token).isPresent(), "the fresh key set knows the new kid");
        assertTrue(gate.callerFor(token).isPresent());
        assertEquals(1, fetches.get(), "the refetched keys are cached, not refetched per request");
    }

    // --- Helpers --------------------------------------------------------------

    private static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String token(KeyPair signer, String kid, String sub, String rolesJson, long exp, String issuer)
            throws Exception {
        return token(signer, kid, sub, rolesJson, exp, issuer, true);
    }

    private String token(KeyPair signer, String kid, String sub, String rolesJson, long exp, String issuer,
                         boolean mfaCompliant) throws Exception {
        String header = b64(("{\"alg\":\"EdDSA\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        String claims = b64(("{\"iss\":\"" + issuer + "\",\"sub\":\"" + sub + "\",\"roles\":" + rolesJson
                + ",\"mfaCompliant\":" + mfaCompliant + ",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(signer.getPrivate());
        signature.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        return header + "." + claims + "." + b64(signature.sign());
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
