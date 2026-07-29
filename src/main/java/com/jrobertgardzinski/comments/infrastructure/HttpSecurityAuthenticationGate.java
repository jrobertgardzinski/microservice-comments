package com.jrobertgardzinski.comments.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Introspecting gate (the default, {@code security.verify=introspect}): asks security's protected
 * {@code GET /me} who the token belongs to — one round-trip per request buys instant revocation
 * awareness. {@link JwtSecurityAuthenticationGate} is the offline alternative.
 */
@Component
@ConditionalOnProperty(name = "security.verify", havingValue = "introspect", matchIfMissing = true)
class HttpSecurityAuthenticationGate implements SecurityAuthenticationGate {

    private final RestClient securityService;

    HttpSecurityAuthenticationGate(@Value("${security.url}") String securityUrl) {
        // bounded waits: without them a hung security service would pin every request thread on
        // token introspection; a timeout falls into the catch below and reads as "not signed in"
        // JdkClientHttpRequestFactory, not SimpleClientHttpRequestFactory. The two timeouts read
        // like a bounded wait and are not one: SimpleClientHttpRequestFactory hands them to
        // HttpURLConnection, whose connect timeout starts AFTER the name is resolved. A DNS
        // server that accepts the query and never answers therefore blocks the request thread
        // for the resolver's own timeout — minutes, on a default glibc — and no amount of
        // tuning these two numbers changes that. The JDK client applies its request timeout to
        // the WHOLE exchange, resolution included, which is the promise this code was making.
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .build());
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.securityService = RestClient.builder().baseUrl(securityUrl)
                .requestFactory(requestFactory).build();
    }

    @Override
    public Optional<Caller> callerFor(String accessToken) {
        try {
            String cid = MDC.get("cid");
            Map<?, ?> body = securityService.get().uri("/me")
                    .header("Authorization", "Bearer " + accessToken)
                    .headers(h -> { if (cid != null) h.add("X-Correlation-Id", cid); })   // trace across services
                    .retrieve().body(Map.class);
            String email = body == null ? null : (String) body.get("email");
            if (email == null) {
                return Optional.empty();
            }
            Set<String> roles = body.get("roles") instanceof Collection<?> raw
                    ? raw.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet())
                    : Set.of("USER");
            return Optional.of(new Caller(email, roles));
        } catch (RestClientException invalidTokenOrServiceDown) {
            return Optional.empty();
        }
    }
}
