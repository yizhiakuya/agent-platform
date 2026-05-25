package com.agentplatform.agent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

public final class ToolInputParser {

    private ToolInputParser() {}

    public static JsonNode parse(Object raw, ObjectMapper mapper, Logger log, String toolName) {
        try {
            if (raw == null) return mapper.createObjectNode();
            JsonNode node = mapper.valueToTree(raw);
            if (node != null && node.isTextual()) {
                return parseTextNode(node, mapper);
            }
            return node == null || node.isNull() ? mapper.createObjectNode() : node;
        } catch (Exception e) {
            if (log != null) {
                log.warn("Failed to parse tool input for {}: {}", toolName, e.getMessage());
            }
            return mapper.createObjectNode();
        }
    }

    private static JsonNode parseTextNode(JsonNode node, ObjectMapper mapper) {
        try {
            return mapper.readTree(node.asText());
        } catch (Exception ignored) {
            return node;
        }
    }
}
