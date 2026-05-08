package com.agentplatform.agent.ai;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public interface ServerToolCallback {

    String name();

    String description();

    JsonNode schema();

    ExecutionResult executeJsonToolUse(JsonNode args, UUID userId, UUID sessionId, ChatEventSink sink);

    default Tool toAnthropicTool(ObjectMapper mapper) {
        Tool.InputSchema.Builder b = Tool.InputSchema.builder()
                .type(JsonValue.from("object"));
        JsonNode schema = schema();
        if (schema != null && schema.isObject()) {
            JsonNode properties = schema.get("properties");
            if (properties != null && properties.isObject()) {
                Tool.InputSchema.Properties.Builder pb = Tool.InputSchema.Properties.builder();
                properties.fields().forEachRemaining(entry -> {
                    Object val = mapper.convertValue(entry.getValue(), Object.class);
                    pb.putAdditionalProperty(entry.getKey(), JsonValue.from(val));
                });
                b.properties(pb.build());
            }
            JsonNode requiredNode = schema.get("required");
            if (requiredNode != null && requiredNode.isArray()) {
                List<String> required = new ArrayList<>();
                requiredNode.forEach(n -> required.add(n.asText()));
                if (!required.isEmpty()) {
                    b.required(required);
                }
            }
            schema.fields().forEachRemaining(e -> {
                String k = e.getKey();
                if (k.equals("type") || k.equals("properties") || k.equals("required")) return;
                Object val = mapper.convertValue(e.getValue(), Object.class);
                b.putAdditionalProperty(k, JsonValue.from(val));
            });
        }
        return Tool.builder()
                .name(name())
                .description(description())
                .inputSchema(b.build())
                .build();
    }
}
