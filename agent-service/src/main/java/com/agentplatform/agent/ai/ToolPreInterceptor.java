package com.agentplatform.agent.ai;

import com.agentplatform.protocol.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

/**
 * Synchronous interceptor that runs before a remote tool call. May:
 *  - return a (possibly rewritten) {@code args} JsonNode to continue dispatch
 *  - throw {@link ToolBlockedException} to abort with a structured error
 *
 * <p>Beans of this type are auto-collected by Spring; ordering via {@code @Order}.
 * Examples: PII redaction, sensitive-tool gating, args validation.
 *
 * <p>{@code requestCtx} carries per-request scalars contributed by
 * {@code ChatService} (today: {@code userId}, {@code sessionId}). The Spring AI
 * {@code ToolContext} wrapper is gone — interceptors that need request-scope
 * state read it out of this map by string key.
 */
public interface ToolPreInterceptor {
    JsonNode before(UUID userId, UUID deviceId, ToolSpec spec, JsonNode args, Map<String, Object> requestCtx);
}
