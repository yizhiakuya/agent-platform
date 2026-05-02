package com.agentplatform.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A single tool exposed by an Android device.
 *
 * <p>Named {@code ToolSpec} (not {@code ToolDefinition}) on purpose: Spring AI ships its own
 * {@code org.springframework.ai.tool.ToolDefinition} that {@code agent-service} also uses,
 * so keeping our protocol-layer name distinct avoids ambiguous imports.
 *
 * @param name             Stable identifier, dot-namespaced, e.g. {@code photos.list_recent}.
 * @param description      Human/LLM-readable summary used in the function-calling prompt.
 * @param schema           JSON Schema describing the {@code args} object accepted by this tool.
 * @param confirmRequired  If {@code true}, server must obtain user approval (handheld
 *                         notification + ConfirmActivity) before the call is executed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolSpec(
        String name,
        String description,
        JsonNode schema,
        boolean confirmRequired
) {}
