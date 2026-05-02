package com.agentplatform.api.hub;

import com.agentplatform.protocol.ToolManifest;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Wire contract of {@code GET /internal/devices/online} on device-hub-service.
 * Consumed by agent-service to decide which tools to inject into the LLM's
 * function-calling list.
 */
public record OnlineDeviceDto(
        UUID deviceId,
        UUID userId,
        ToolManifest manifest,
        OffsetDateTime connectedAt
) {}
