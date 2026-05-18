package com.agentplatform.agent.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * @param message   user's free-form text. Optional when at least one image is attached.
 * @param sessionId existing session to append to. When null, agent-service asks
 *                  chat-service to create a fresh session for this user.
 * @param deviceId  target device. Optional — when null, server picks the user's
 *                  first online device.
 * @param attachments images uploaded through /api/uploads/photos for this turn.
 */
public record ChatRequest(
        String message,
        UUID sessionId,
        UUID deviceId,
        @Valid @Size(max = 4) List<ChatImageAttachment> attachments,
        @Size(max = 96) String clientRunId
) {
    public ChatRequest(String message, UUID sessionId, UUID deviceId) {
        this(message, sessionId, deviceId, List.of(), null);
    }

    public ChatRequest(String message, UUID sessionId, UUID deviceId,
                       List<ChatImageAttachment> attachments) {
        this(message, sessionId, deviceId, attachments, null);
    }

    public ChatRequest {
        message = message == null ? "" : message;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        clientRunId = clientRunId == null || clientRunId.isBlank() ? null : clientRunId.trim();
    }

    @AssertTrue(message = "message or image attachment required")
    public boolean hasMessageOrAttachment() {
        return !message.isBlank() || !attachments.isEmpty();
    }
}
