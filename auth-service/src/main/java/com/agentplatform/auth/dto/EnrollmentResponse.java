package com.agentplatform.auth.dto;

import java.time.OffsetDateTime;

/**
 * @param token      Raw enrollment token (single-use, plaintext). Caller turns this
 *                   into a QR code; we never store it.
 * @param qrPayload  Convenience pre-built QR string of the form
 *                   {@code agent-platform://enroll?server=...&token=...}.
 * @param expiresAt  When the token will be auto-rejected at redeem time.
 */
public record EnrollmentResponse(String token, String qrPayload, OffsetDateTime expiresAt) {}
