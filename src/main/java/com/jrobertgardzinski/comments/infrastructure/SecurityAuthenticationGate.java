package com.jrobertgardzinski.comments.infrastructure;

import java.util.Optional;

/**
 * Boundary gate to microservice-security: resolves an access token to the signed-in user's
 * e-mail, or empty when the token is missing, invalid or expired.
 */
interface SecurityAuthenticationGate {

    Optional<String> emailFor(String accessToken);
}
