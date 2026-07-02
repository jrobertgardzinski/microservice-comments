package com.jrobertgardzinski.comments.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Reading comments is public, writing requires signing in: every POST must carry a bearer token
 * that microservice-security confirms; the confirmed e-mail is published as the
 * {@link #AUTHENTICATED_USER} request attribute (comment authors and voters come from there,
 * never from the request body). Reads resolve the identity too when a token is presented — that
 * is how listings can show "your vote".
 */
@Component
class RequireSignInFilter extends OncePerRequestFilter {

    static final String AUTHENTICATED_USER = "authenticatedUser";

    private final SecurityAuthenticationGate gate;

    RequireSignInFilter(SecurityAuthenticationGate gate) {
        this.gate = gate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/memes")) {
            chain.doFilter(request, response);
            return;
        }
        Optional<String> user = bearerToken(request).flatMap(gate::emailFor);
        user.ifPresent(email -> request.setAttribute(AUTHENTICATED_USER, email));
        if ("POST".equals(request.getMethod()) && user.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":\"SIGN_IN_REQUIRED\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.startsWith("Bearer ")
                ? Optional.of(header.substring("Bearer ".length()))
                : Optional.empty();
    }
}
