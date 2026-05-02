package com.agentplatform.auth.dto;

/**
 * Result of {@code POST /internal/auth/verify}. When {@code valid=false}, all
 * other fields are null and {@code error} carries a short reason string.
 */
public record VerifyResponse(
        boolean valid,
        String type,
        String subject,
        String userId,
        String jti,
        String error
) {}
