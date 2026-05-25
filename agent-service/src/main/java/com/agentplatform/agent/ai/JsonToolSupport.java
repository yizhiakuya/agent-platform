package com.agentplatform.agent.ai;

import com.agentplatform.agent.client.InternalChatFeignClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.function.Consumer;

abstract class JsonToolSupport {

    protected final InternalChatFeignClient chatClient;
    protected final ObjectMapper mapper;

    protected JsonToolSupport(InternalChatFeignClient chatClient, ObjectMapper mapper) {
        this.chatClient = chatClient;
        this.mapper = mapper;
    }

    protected final ObjectNode schema(Consumer<ObjectNode> configureProperties, String... requiredFields) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        schema.set("properties", props);
        configureProperties.accept(props);
        if (requiredFields != null && requiredFields.length > 0) {
            ArrayNode required = mapper.createArrayNode();
            for (String field : requiredFields) {
                required.add(field);
            }
            schema.set("required", required);
        }
        return schema;
    }

    protected final ObjectNode prop(String type, String description) {
        ObjectNode p = mapper.createObjectNode();
        p.put("type", type);
        p.put("description", description);
        return p;
    }

    protected final String text(JsonNode args, String field) {
        return text(args, field, "");
    }

    protected final String text(JsonNode args, String field, String fallback) {
        if (args == null || !args.has(field)) return fallback;
        JsonNode node = args.get(field);
        return node == null || node.isNull() ? fallback : node.asText(fallback).trim();
    }
}
