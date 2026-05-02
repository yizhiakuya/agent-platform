package com.agentplatform.agent.chat;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * @param message   user's free-form text. Required.
 * @param sessionId existing session to append to. When null, agent-service asks
 *                  chat-service to create a fresh session for this user.
 * @param deviceId  target device. Optional — when null, server picks the user's
 *                  first online device.
 */
public record ChatRequest(
        @NotBlank String message,
        UUID sessionId,
        UUID deviceId
) {}
