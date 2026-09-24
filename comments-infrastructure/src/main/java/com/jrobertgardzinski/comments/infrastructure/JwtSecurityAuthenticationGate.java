package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.offlinejwt.OfflineJwtVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.PublicKey;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Offline {@link SecurityAuthenticationGate} ({@code security.verify=offline}): instead of asking
 * {@code GET /me} per request, the access token's own EdDSA signature is verified against the
 * public keys security serves at {@code /.well-known/jwks.json} and the caller (subject + roles)
 * is read from the claims. The trade-off is revocation blindness until the token's {@code exp}.
 *
 * <p>The verification core is the shared offline-jwt library — it used to be a local copy with
 * four drifting twins. What stays HERE is this service's policy: the MFA floor withholds
 * privileged roles from an under-enrolled account; it never blocks sign-in.
 */
@Component
@ConditionalOnProperty(name = "security.verify", havingValue = "offline")
class JwtSecurityAuthenticationGate implements SecurityAuthenticationGate {

    private final OfflineJwtVerifier verifier;

    @org.springframework.beans.factory.annotation.Autowired
    JwtSecurityAuthenticationGate(@Value("${security.url}") String securityUrl, ObjectMapper mapper) {
        this(OfflineJwtVerifier.overHttp(securityUrl, mapper));
    }

    JwtSecurityAuthenticationGate(Supplier<Map<String, PublicKey>> jwksFetcher, ObjectMapper mapper) {
        this(new OfflineJwtVerifier(jwksFetcher, mapper));
    }

    private JwtSecurityAuthenticationGate(OfflineJwtVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public Optional<Caller> callerFor(String accessToken) {
        return verifier.verify(accessToken).map(verified -> new Caller(verified.subject(),
                Caller.withMfaFloor(verified.roles(), verified.mfaCompliant())));
    }
}
