package com.agentplatform.api.hub;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Wire contract of {@code POST /internal/tools/call} on device-hub-service.
 * Issued by agent-service when the LLM decides to invoke a tool on a device.
 *
 * @param deviceId   target device. Must be online (or auto-provisionable in mock-mode).
 * @param userId     owning user. Used in mock-mode to wire the auto-created
 *                   {@code MockDeviceSession} to the right user. Ignored in
 *                   real-WS mode (the device session already knows its owner).
 * @param toolName   tool to invoke (must match a name from the device's manifest).
 * @param args       JSON-Schema-validated arguments. May be null/empty.
 * @param timeoutMs  optional override; falls back to {@code agent-platform.hub.tool-call-timeout-ms}.
 */
public record InternalCallRequest(
        UUID deviceId,
        UUID userId,
        String toolName,
        JsonNode args,
        Long timeoutMs
) {}
