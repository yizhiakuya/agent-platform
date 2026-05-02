package com.agentplatform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Read gateway-injected trust headers ({@code X-Principal-Type} / {@code X-User-Id} /
 * {@code X-Device-Id} / {@code X-Jti}) and expose the resulting {@link Principal}
 * via {@link PrincipalContext}. The gateway is responsible for verifying the JWT
 * exactly once and forwarding these headers; downstream services trust them.
 *
 * <p><strong>Security note</strong>: this filter MUST only be wired up on internal
 * endpoints behind the gateway. Direct exposure of an endpoint using this filter
 * would let any client spoof identity by setting these headers.
 */
public class TrustedHeaderAuthFilter extends AbstractAuthFilter {

    public static final String H_TYPE = "X-Principal-Type";
    public static final String H_USER = "X-User-Id";
    public static final String H_DEVICE = "X-Device-Id";
    public static final String H_JTI = "X-Jti";

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {
        String type = req.getHeader(H_TYPE);
        if (type != null) {
            String userId = req.getHeader(H_USER);
            String deviceId = req.getHeader(H_DEVICE);
            String jti = req.getHeader(H_JTI);
            String subject = Principal.TYPE_DEVICE.equals(type) ? deviceId : userId;
            PrincipalContext.set(new Principal(type, subject, userId, jti));
        }
        try {
            chain.doFilter(req, res);
        } finally {
            PrincipalContext.clear();
        }
    }
}
