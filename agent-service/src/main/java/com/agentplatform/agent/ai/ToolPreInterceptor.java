package com.agentplatform.agent.ai;

import com.agentplatform.protocol.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.chat.model.ToolContext;

import java.util.UUID;

/**
 * Synchronous interceptor that runs before a remote tool call. May:
 *  - return a (possibly rewritten) {@code args} JsonNode to continue dispatch
 *  - throw {@link ToolBlockedException} to abort with a structured error
 *
 * Beans of this type are auto-collected by Spring; ordering via {@code @Order}.
 * Examples: PII redaction, sensitive-tool gating, args validation.
 */
public interface ToolPreInterceptor {
    JsonNode before(UUID userId, UUID deviceId, ToolSpec spec, JsonNode args, ToolContext ctx);
}
