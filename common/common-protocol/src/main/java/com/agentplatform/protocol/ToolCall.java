package com.agentplatform.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Payload of a {@link JsonRpcMethods#TOOL_CALL} request: which tool to invoke and what args.
 * The {@code args} structure is constrained by the {@link ToolSpec#schema()} for that tool.
 */
public record ToolCall(String tool, JsonNode args) {}
