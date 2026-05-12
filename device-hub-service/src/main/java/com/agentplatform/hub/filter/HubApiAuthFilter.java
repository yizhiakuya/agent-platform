package com.agentplatform.hub.filter;

import com.agentplatform.security.AbstractAuthFilter;
import com.agentplatform.security.InternalToken;
import com.agentplatform.security.JwtUtil;
import com.agentplatform.security.Principal;
import com.agentplatform.security.PrincipalContext;
import com.agentplatform.security.TrustedHeaderAuthFilter;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

public class HubApiAuthFilter extends AbstractAuthFilter {

    private static final Logger log = LoggerFactory.getLogger(HubApiAuthFilter.class);
    private static final String BEARER = "Bearer ";

    private final JwtUtil jwt;
    private final String internalToken;
    private final List<String> protectedPrefixes;

    public HubApiAuthFilter(JwtUtil jwt, String internalToken, List<String> protectedPrefixes) {
        this.jwt = jwt;
        this.internalToken = internalToken;
        this.protectedPrefixes = protectedPrefixes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        PrincipalContext.clear();
        try {
            Principal principal = resolveTrustedHeaders(req);
            if (principal == null) {
                principal = resolveBearer(req);
            }
            if (principal != null) {
                PrincipalContext.set(principal);
            }

            boolean isProtected = protectedPrefixes.stream().anyMatch(req.getRequestURI()::startsWith);
            if (isProtected && principal == null) {
                res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
                return;
            }

            chain.doFilter(req, res);
        } finally {
            PrincipalContext.clear();
        }
    }

    private Principal resolveTrustedHeaders(HttpServletRequest req) {
        if (!InternalToken.isValid(internalToken, req.getHeader(InternalToken.HEADER))) {
            return null;
        }
        String type = req.getHeader(TrustedHeaderAuthFilter.H_TYPE);
        if (type == null) {
            return null;
        }
        String userId = req.getHeader(TrustedHeaderAuthFilter.H_USER);
        String deviceId = req.getHeader(TrustedHeaderAuthFilter.H_DEVICE);
        String jti = req.getHeader(TrustedHeaderAuthFilter.H_JTI);
        String subject = Principal.TYPE_DEVICE.equals(type) ? deviceId : userId;
        return new Principal(type, subject, userId, jti);
    }

    private Principal resolveBearer(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            return null;
        }
        try {
            return jwt.verify(header.substring(BEARER.length()));
        } catch (JwtException e) {
            log.debug("Bearer token rejected: {}", e.getMessage());
            return null;
        }
    }
}
