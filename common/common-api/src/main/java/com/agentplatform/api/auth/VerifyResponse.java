package com.agentplatform.api.auth;

/**
 * Result of internal token verification.
 */
public record VerifyResponse(
        boolean valid,
        String type,
        String subject,
        String userId,
        String jti,
        String error
) {}
