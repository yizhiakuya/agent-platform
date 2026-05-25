package com.agentplatform.agent.ai;

import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;

public interface ServerToolCallback {

    String name();

    String description();

    JsonNode schema();

    ExecutionResult executeJsonToolUse(JsonNode args, UUID userId, UUID sessionId, ChatEventSink sink);

    default Tool toAnthropicTool(ObjectMapper mapper) {
        return Tool.builder()
                .name(name())
                .description(description())
                .inputSchema(AnthropicToolSchema.inputSchema(schema(), mapper))
                .build();
    }
}
