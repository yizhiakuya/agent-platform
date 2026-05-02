package com.agentplatform.api.auth;

import java.time.OffsetDateTime;

/**
 * Per-user preference document — free-form markdown that is injected into the
 * LLM system prompt. Returned by both the public {@code /api/me/preferences}
 * endpoint and the internal {@code /internal/users/{id}/preferences} endpoint
 * that agent-service calls.
 */
public record UserPreferenceDto(String content, OffsetDateTime updatedAt) {}
