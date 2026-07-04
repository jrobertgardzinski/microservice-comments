package com.jrobertgardzinski.comments.infrastructure;

import java.util.Set;

/**
 * Who is calling, as microservice-security reports them via {@code GET /me}: the signed-in e-mail
 * and their roles. A MODERATOR or ADMIN may act on other people's comments; a plain USER only on
 * their own.
 */
record Caller(String email, Set<String> roles) {

    boolean isModerator() {
        return roles.contains("MODERATOR") || roles.contains("ADMIN");
    }
}
