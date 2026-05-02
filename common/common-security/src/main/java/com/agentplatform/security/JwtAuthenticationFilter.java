package com.agentplatform.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Servlet filter that verifies a {@code Bearer <jwt>} {@code Authorization} header,
 * stores the resulting {@link Principal} in {@link PrincipalContext}, and clears
 * it after the chain completes.
 *
 * <p>Use this on services that may receive direct (non-gateway) traffic, e.g.
 * {@code auth-service}. Behind the gateway, prefer {@link TrustedHeaderAuthFilter}
 * so JWT verification only happens once at the edge.
 *
 * @param optional When true, requests without a token are allowed through (the
 *                 controller decides what to do). When false, missing/invalid tokens
 *                 produce a 401.
 */
public class JwtAuthenticationFilter extends AbstractAuthFilter {

    private static final String BEARER = "Bearer ";

    private final JwtUtil jwt;
    private final boolean optional;

    public JwtAuthenticationFilter(JwtUtil jwt, boolean optional) {
        this.jwt = jwt;
        this.optional = optional;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            if (optional) {
                chain.doFilter(req, res);
                return;
            }
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing bearer token");
            return;
        }
        try {
            Principal p = jwt.verify(header.substring(BEARER.length()));
            PrincipalContext.set(p);
            chain.doFilter(req, res);
        } catch (JwtException e) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid token");
        } finally {
            PrincipalContext.clear();
        }
    }
}
