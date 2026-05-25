package com.agentplatform.agent.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * Lightweight SSE event envelope. Web client sees:
 * <pre>
 * event: tool_call_started
 * data: {"deviceId":"...","tool":"photos.list_recent","args":{"limit":5}}
 *
 * event: tool_call_result
 * data: {"tool":"photos.list_recent","result":{...}}
 * </pre>
 *
 * <p>Common event types:
 * <ul>
 *   <li>{@code user_message}      — echoes the user's prompt back to the stream</li>
 *   <li>{@code assistant_message} — streamed or final LLM text</li>
 *   <li>{@code tool_call_started} — about to call a tool, includes deviceId/tool/args</li>
 *   <li>{@code tool_call_result}  — tool returned, includes raw result JSON</li>
 *   <li>{@code error}             — any failure, includes message</li>
 * </ul>
 */
public record SseEvent(String type, JsonNode data) {

    private static final String CONTENT_FIELD = "content";

    public static SseEvent session(ObjectMapper mapper, UUID sessionId) {
        return new SseEvent("session",
                mapper.createObjectNode().put("sessionId", sessionId == null ? null : sessionId.toString()));
    }

    public static SseEvent userMessage(ObjectMapper mapper, String content) {
        return new SseEvent("user_message", mapper.createObjectNode().put(CONTENT_FIELD, content));
    }

    public static SseEvent userMessage(ObjectMapper mapper, String content, JsonNode attachments) {
        ObjectNode data = mapper.createObjectNode();
        data.put(CONTENT_FIELD, content == null ? "" : content);
        if (attachments != null && attachments.isArray() && !attachments.isEmpty()) {
            data.set("attachments", attachments);
        }
        return new SseEvent("user_message", data);
    }

    public static SseEvent assistantMessage(ObjectMapper mapper, String content) {
        return new SseEvent("assistant_message", mapper.createObjectNode().put(CONTENT_FIELD, content));
    }

    public static SseEvent toolCallStarted(ObjectMapper mapper, UUID deviceId,
                                            String toolName, JsonNode args) {
        ObjectNode data = mapper.createObjectNode();
        data.put("deviceId", deviceId == null ? null : deviceId.toString());
        data.put("tool", toolName);
        data.set("args", args == null ? mapper.createObjectNode() : args);
        return new SseEvent("tool_call_started", data);
    }

    public static SseEvent toolCallResult(ObjectMapper mapper, String toolName, JsonNode result) {
        ObjectNode data = mapper.createObjectNode();
        data.put("tool", toolName);
        data.set("result", result == null ? mapper.createObjectNode() : result);
        return new SseEvent("tool_call_result", data);
    }

    public static SseEvent toolCallError(ObjectMapper mapper, String toolName, String message) {
        ObjectNode error = mapper.createObjectNode();
        error.put("message", message == null ? "tool failed" : message);
        ObjectNode result = mapper.createObjectNode();
        result.set("error", error);
        return toolCallResult(mapper, toolName, result);
    }

    public static SseEvent error(ObjectMapper mapper, String message) {
        return new SseEvent("error", mapper.createObjectNode().put("message", message));
    }
}
