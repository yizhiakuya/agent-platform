package com.agentplatform.agent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Application event published after a remote tool call completes (success or
 * error). Consumed asynchronously by listeners such as
 * {@link ToolAuditListener} for audit / metrics. Fire-and-forget — must not
 * influence the synchronous tool-call path.
 */
public record ToolPostEvent(
        UUID userId,
        UUID deviceId,
        String toolName,
        JsonNode args,
        @Nullable JsonNode result,    // null when error
        @Nullable String errorMessage,
        long durationMs,
        Instant occurredAt
) {}
