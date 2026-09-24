package com.jrobertgardzinski.comments.infrastructure;

import java.util.Set;

/**
 * Who is calling, as microservice-security reports them via {@code GET /me}: the signed-in e-mail
 * and their roles. A MODERATOR or ADMIN may act on other people's comments; a plain USER only on
 * their own.
 */
record Caller(String email, Set<String> roles) {

    private static final Set<String> PRIVILEGED = Set.of("MODERATOR", "ADMIN");

    boolean isModerator() {
        return roles.contains("MODERATOR") || roles.contains("ADMIN");
    }

    /**
     * The MFA floor, enforced consumer-side: an under-enrolled privileged account is served as a
     * plain USER — its MODERATOR/ADMIN roles are withheld until the token says it is compliant, so
     * every existing role check enforces the floor with no per-endpoint code.
     */
    static Set<String> withMfaFloor(Set<String> roles, boolean mfaCompliant) {
        if (mfaCompliant || roles.stream().noneMatch(PRIVILEGED::contains)) {
            return roles;
        }
        return roles.stream().filter(role -> !PRIVILEGED.contains(role))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
