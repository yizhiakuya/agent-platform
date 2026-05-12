package com.agentplatform.auth.filter;

import com.agentplatform.security.AbstractAuthFilter;
import com.agentplatform.security.InternalToken;
import com.agentplatform.security.JwtUtil;
import com.agentplatform.security.Principal;
import com.agentplatform.security.PrincipalContext;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

/**
 * Path-based JWT auth filter.
 * <ul>
 *   <li>If the request path starts with one of {@code protectedPrefixes},
 *       a valid Bearer JWT is required — otherwise 401.</li>
 *   <li>For all other paths, a Bearer token is parsed best-effort if present
 *       (so controllers can opportunistically know the caller), but its
 *       absence/invalidity is not an error.</li>
 * </ul>
 * <p>Behind the gateway in production this is replaced by
 * {@code TrustedHeaderAuthFilter}; this filter still protects direct dev
 * traffic such as /api/me/** when the service runs standalone.
 */
public class PathBasedJwtFilter extends AbstractAuthFilter {

    private static final String BEARER = "Bearer ";

    private final JwtUtil jwt;
    private final String internalToken;
    private final List<String> protectedPrefixes;

    public PathBasedJwtFilter(JwtUtil jwt, String internalToken, List<String> protectedPrefixes) {
        this.jwt = jwt;
        this.internalToken = internalToken;
        this.protectedPrefixes = protectedPrefixes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {
        PrincipalContext.clear();
        try {
            doFilterWithCleanContext(req, res, chain);
        } finally {
            PrincipalContext.clear();
        }
    }

    private void doFilterWithCleanContext(HttpServletRequest req,
                                          HttpServletResponse res,
                                          FilterChain chain)
            throws ServletException, IOException {
        boolean isProtected = isProtected(req.getRequestURI());
        boolean isInternal = req.getRequestURI().startsWith("/internal/");
        if (isInternal && !InternalToken.isValid(internalToken, req.getHeader(InternalToken.HEADER))) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Internal token required");
            return;
        }
        String header = req.getHeader("Authorization");
        Principal principal = null;

        if (header != null && header.startsWith(BEARER)) {
            try {
                principal = jwt.verify(header.substring(BEARER.length()));
                PrincipalContext.set(principal);
            } catch (JwtException e) {
                if (isProtected) {
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid token");
                    return;
                }
                // best-effort on public paths — fall through unauthenticated
            }
        }

        if (isProtected && principal == null) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
            return;
        }

        chain.doFilter(req, res);
    }

    private boolean isProtected(String path) {
        return protectedPrefixes.stream().anyMatch(path::startsWith);
    }
}
