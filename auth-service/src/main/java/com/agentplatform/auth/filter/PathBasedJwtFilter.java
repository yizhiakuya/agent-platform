package com.agentplatform.auth.filter;

import com.agentplatform.security.AbstractAuthFilter;
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
 * {@code TrustedHeaderAuthFilter} (auth-service won't see direct traffic).
 * For PR 3 this filter is what makes /api/me/** secure when running standalone.
 */
public class PathBasedJwtFilter extends AbstractAuthFilter {

    private static final String BEARER = "Bearer ";

    private final JwtUtil jwt;
    private final List<String> protectedPrefixes;

    public PathBasedJwtFilter(JwtUtil jwt, List<String> protectedPrefixes) {
        this.jwt = jwt;
        this.protectedPrefixes = protectedPrefixes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {
        boolean isProtected = isProtected(req.getRequestURI());
        String header = req.getHeader("Authorization");

        if (header != null && header.startsWith(BEARER)) {
            try {
                Principal p = jwt.verify(header.substring(BEARER.length()));
                PrincipalContext.set(p);
            } catch (JwtException e) {
                if (isProtected) {
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid token");
                    return;
                }
                // best-effort on public paths — fall through unauthenticated
            }
        }

        if (isProtected && PrincipalContext.current() == null) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
            return;
        }

        try {
            chain.doFilter(req, res);
        } finally {
            PrincipalContext.clear();
        }
    }

    private boolean isProtected(String path) {
        return protectedPrefixes.stream().anyMatch(path::startsWith);
    }
}
