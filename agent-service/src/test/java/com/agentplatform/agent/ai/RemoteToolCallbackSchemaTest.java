package com.agentplatform.agent.ai;

import com.agentplatform.protocol.ToolSpec;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteToolCallbackSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void omitsAnthropicUnsupportedRootCombinators() throws Exception {
        JsonNode schema = mapper.readTree("""
                {
                  "type": "object",
                  "properties": {
                    "id": { "type": "string" },
                    "ids": {
                      "type": "array",
                      "items": { "type": "string" }
                    },
                    "mode": {
                      "anyOf": [
                        { "const": "single" },
                        { "const": "batch" }
                      ]
                    }
                  },
                  "required": ["album"],
                  "anyOf": [
                    { "required": ["id"] },
                    { "required": ["ids"] }
                  ],
                  "oneOf": [
                    { "required": ["id"] }
                  ],
                  "allOf": [
                    { "required": ["album"] }
                  ],
                  "additionalProperties": false
                }
                """);
        RemoteToolCallback callback = new RemoteToolCallback(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new ToolSpec("photos.copy_to_album", "copy photos", schema, true),
                null,
                mapper,
                List.of(),
                null);

        Tool.InputSchema inputSchema = callback.toAnthropicTool().inputSchema();

        assertFalse(inputSchema._additionalProperties().containsKey("anyOf"));
        assertFalse(inputSchema._additionalProperties().containsKey("oneOf"));
        assertFalse(inputSchema._additionalProperties().containsKey("allOf"));
        assertTrue(inputSchema._additionalProperties().containsKey("additionalProperties"));
        assertEquals(List.of("album"), inputSchema.required().orElseThrow());
        Tool.InputSchema.Properties properties = inputSchema.properties().orElseThrow();
        assertTrue(properties._additionalProperties().containsKey("id"));
        assertTrue(properties._additionalProperties().containsKey("ids"));
        assertTrue(properties._additionalProperties().containsKey("mode"));
    }
}
