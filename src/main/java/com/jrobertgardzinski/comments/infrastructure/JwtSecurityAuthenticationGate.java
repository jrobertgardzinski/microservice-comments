package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Offline {@link SecurityAuthenticationGate} ({@code security.verify=offline}): instead of asking
 * {@code GET /me} per request, it verifies the access token's own EdDSA signature against the
 * public keys security serves at {@code /.well-known/jwks.json} and reads the caller (subject +
 * roles) from the claims. One JWKS fetch amortises over every request — the deliberate trade-off
 * is revocation blindness: a logout or role change is invisible until the token's {@code exp}
 * (an hour by default). Keys are cached; an unknown {@code kid} triggers one refetch, which also
 * covers security restarting with fresh ephemeral keys.
 */
@Component
@ConditionalOnProperty(name = "security.verify", havingValue = "offline")
class JwtSecurityAuthenticationGate implements SecurityAuthenticationGate {

    private static final String EXPECTED_ISSUER = "microservice-security";
    private static final byte[] ED25519_DER_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private final Supplier<Map<String, PublicKey>> jwksFetcher;
    private final ObjectMapper mapper;
    private final AtomicReference<Map<String, PublicKey>> cachedKeys = new AtomicReference<>(Map.of());

    JwtSecurityAuthenticationGate(@Value("${security.url}") String securityUrl, ObjectMapper mapper) {
        this(jwksOver(RestClient.create(securityUrl), mapper), mapper);
    }

    JwtSecurityAuthenticationGate(Supplier<Map<String, PublicKey>> jwksFetcher, ObjectMapper mapper) {
        this.jwksFetcher = jwksFetcher;
        this.mapper = mapper;
    }

    @Override
    public Optional<Caller> callerFor(String accessToken) {
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length != 3) {
                return Optional.empty();
            }
            JsonNode header = json(parts[0]);
            if (!"EdDSA".equals(header.path("alg").asText())) {
                return Optional.empty();
            }
            PublicKey key = keyFor(header.path("kid").asText());
            if (key == null || !signatureVerifies(key, parts)) {
                return Optional.empty();
            }
            JsonNode claims = json(parts[1]);
            if (!EXPECTED_ISSUER.equals(claims.path("iss").asText())
                    || claims.path("exp").asLong() <= Instant.now().getEpochSecond()) {
                return Optional.empty();
            }
            String email = claims.path("sub").asText();
            if (email.isBlank()) {
                return Optional.empty();
            }
            Set<String> roles = claims.path("roles").isArray()
                    ? StreamSupport.stream(claims.path("roles").spliterator(), false)
                            .map(JsonNode::asText).collect(Collectors.toUnmodifiableSet())
                    : Set.of("USER");
            return Optional.of(new Caller(email, roles));
        } catch (Exception invalidTokenOrJwksDown) {
            return Optional.empty();
        }
    }

    private PublicKey keyFor(String kid) {
        PublicKey known = cachedKeys.get().get(kid);
        if (known != null) {
            return known;
        }
        // unknown kid: maybe a rotation (or security restarted with fresh keys) — refetch once
        Map<String, PublicKey> fresh = jwksFetcher.get();
        cachedKeys.set(fresh);
        return fresh.get(kid);
    }

    private static boolean signatureVerifies(PublicKey key, String[] parts) throws Exception {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        return verifier.verify(Base64.getUrlDecoder().decode(parts[2]));
    }

    private JsonNode json(String base64Url) throws Exception {
        return mapper.readTree(Base64.getUrlDecoder().decode(base64Url));
    }

    private static Supplier<Map<String, PublicKey>> jwksOver(RestClient security, ObjectMapper mapper) {
        return () -> {
            try {
                JsonNode set = mapper.readTree(
                        security.get().uri("/.well-known/jwks.json").retrieve().body(String.class));
                Map<String, PublicKey> byKid = new HashMap<>();
                for (JsonNode jwk : set.path("keys")) {
                    if ("OKP".equals(jwk.path("kty").asText()) && "Ed25519".equals(jwk.path("crv").asText())) {
                        byKid.put(jwk.path("kid").asText(), publicKeyFrom(jwk.path("x").asText()));
                    }
                }
                return Map.copyOf(byKid);
            } catch (Exception jwksUnavailable) {
                return Map.of();   // no keys, no callers — fails closed
            }
        };
    }

    /** Rebuild the Ed25519 public key from a JWK's raw {@code x}: fixed DER prefix + 32 raw bytes. */
    private static PublicKey publicKeyFrom(String x) throws Exception {
        byte[] raw = Base64.getUrlDecoder().decode(x);
        byte[] encoded = new byte[ED25519_DER_PREFIX.length + raw.length];
        System.arraycopy(ED25519_DER_PREFIX, 0, encoded, 0, ED25519_DER_PREFIX.length);
        System.arraycopy(raw, 0, encoded, ED25519_DER_PREFIX.length, raw.length);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
    }
}
