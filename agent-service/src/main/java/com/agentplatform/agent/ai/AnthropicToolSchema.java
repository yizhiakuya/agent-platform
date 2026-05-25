package com.agentplatform.agent.ai;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class AnthropicToolSchema {

    private AnthropicToolSchema() {
    }

    static Tool.InputSchema inputSchema(JsonNode schema, ObjectMapper mapper) {
        return inputSchema(schema, mapper, Set.of());
    }

    static Tool.InputSchema inputSchema(JsonNode schema, ObjectMapper mapper, Set<String> ignoredRootKeys) {
        Tool.InputSchema.Builder builder = Tool.InputSchema.builder()
                .type(JsonValue.from("object"));
        if (schema == null || schema.isNull() || !schema.isObject()) {
            return builder.build();
        }
        addProperties(builder, schema, mapper);
        addRequired(builder, schema);
        addAdditionalRootProperties(builder, schema, mapper, ignoredRootKeys);
        return builder.build();
    }

    private static void addProperties(Tool.InputSchema.Builder builder, JsonNode schema, ObjectMapper mapper) {
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject()) {
            return;
        }
        Tool.InputSchema.Properties.Builder propertiesBuilder = Tool.InputSchema.Properties.builder();
        properties.fields().forEachRemaining(entry -> {
            Object value = mapper.convertValue(entry.getValue(), Object.class);
            propertiesBuilder.putAdditionalProperty(entry.getKey(), JsonValue.from(value));
        });
        builder.properties(propertiesBuilder.build());
    }

    private static void addRequired(Tool.InputSchema.Builder builder, JsonNode schema) {
        JsonNode requiredNode = schema.get("required");
        if (requiredNode == null || !requiredNode.isArray()) {
            return;
        }
        List<String> required = new ArrayList<>();
        requiredNode.forEach(node -> required.add(node.asText()));
        if (!required.isEmpty()) {
            builder.required(required);
        }
    }

    private static void addAdditionalRootProperties(
            Tool.InputSchema.Builder builder,
            JsonNode schema,
            ObjectMapper mapper,
            Set<String> ignoredRootKeys) {
        schema.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (key.equals("type") || key.equals("properties") || key.equals("required")) {
                return;
            }
            if (ignoredRootKeys.contains(key)) {
                return;
            }
            Object value = mapper.convertValue(entry.getValue(), Object.class);
            builder.putAdditionalProperty(key, JsonValue.from(value));
        });
    }
}
