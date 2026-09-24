package com.jrobertgardzinski.comments.infrastructure;

import java.util.Optional;

/**
 * Boundary gate to microservice-security: resolves an access token to the {@link Caller} (e-mail +
 * roles), or empty when the token is missing, invalid or expired.
 */
interface SecurityAuthenticationGate {

    Optional<Caller> callerFor(String accessToken);
}
