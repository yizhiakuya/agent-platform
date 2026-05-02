package com.agentplatform.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Outcome of a {@link ToolCall}. Exactly one of {@code value}/{@code error} is non-null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResult(JsonNode value, JsonRpcError error) {

    public static ToolResult ok(JsonNode value) {
        return new ToolResult(value, null);
    }

    public static ToolResult err(JsonRpcError error) {
        return new ToolResult(null, error);
    }

    public boolean hasError() {
        return error != null;
    }
}
