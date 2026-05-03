package com.agentplatform.agent.ai;

import java.util.List;

/**
 * Outcome of one local tool execution, as produced by
 * {@link RemoteToolCallback#executeToolUse} / {@link SkillLoadCallback#executeToolUse}.
 *
 * <ul>
 *   <li>{@code jsonText} — text content for the {@code tool_result} block. For a
 *   device tool this is the raw response JSON with any {@code *_b64} fields
 *   stripped out (so the LLM doesn't see 100KB base64 strings inline). For
 *   {@code skill_load} it's the skill's markdown body.
 *   <li>{@code images} — vision images extracted from the same response. Empty
 *   for non-image tools and for skill_load. {@code ChatService} folds each one
 *   into a sibling {@code ImageBlockParam} on the same {@code tool_result} so
 *   the LLM can see the bytes natively (Anthropic multimodal {@code tool_result}).
 * </ul>
 *
 * <p>Static factories cover the common no-image and error paths.
 */
public record ExecutionResult(String jsonText, List<PendingImage> images) {

    /** No images attached. Use for tools that only return text/JSON. */
    public static ExecutionResult text(String json) {
        return new ExecutionResult(json, List.of());
    }

    /**
     * Wrap an error message into a tiny JSON object so the LLM still sees a
     * valid {@code tool_result}. Used for unknown-tool dispatch and for
     * RuntimeExceptions that bubble out of the dispatcher.
     */
    public static ExecutionResult error(String message) {
        String safe = message == null ? "unknown error" : message.replace("\"", "\\\"");
        return new ExecutionResult("{\"error\":\"" + safe + "\"}", List.of());
    }
}
