package com.agentplatform.api.chat;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * @param sessionId  Target session. Required.
 * @param userId     Owner check — chat-service rejects writes to sessions that
 *                   don't belong to this user. Required.
 * @param role       USER / ASSISTANT / TOOL_CALL / TOOL_RESULT.
 * @param content    Plain text body. PII sanitiser may shrink this before storage.
 * @param metadata   Optional structured payload, e.g. for TOOL_CALL it carries
 *                   {@code {"tool":"...","args":{...},"deviceId":"..."}}; for
 *                   TOOL_RESULT it carries {@code {"tool":"...","result":{...}}}.
 */
public record WriteMessageRequest(
        UUID sessionId,
        UUID userId,
        MessageRole role,
        String content,
        JsonNode metadata
) {}
