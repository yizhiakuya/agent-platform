package com.agentplatform.agent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class ServerToolRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void exposesServerToolsForLlmAndDispatch() {
        ServerToolCallback callback = new StubServerTool();
        ServerToolRegistry registry = new ServerToolRegistry(List.of(callback), mapper);

        assertThat(registry.dispatchMap()).containsKey("agent_memory_add");
        assertThat(registry.toAnthropicTools())
                .singleElement()
                .satisfies(tool -> {
                    assertThat(tool.name()).isEqualTo("agent_memory_add");
                    assertThat(tool.description()).isPresent();
                    assertThat(tool.description().orElseThrow()).contains("memory");
                });
    }

    @Test
    void rejectsDuplicateToolNames() {
        ServerToolCallback callback = new StubServerTool();

        assertThatThrownBy(() -> new ServerToolRegistry(List.of(callback, callback), mapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate server tool name: agent_memory_add");
    }

    private class StubServerTool implements ServerToolCallback {
        @Override
        public String name() {
            return "agent_memory_add";
        }

        @Override
        public String description() {
            return "Add a memory.";
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = mapper.createObjectNode();
            ObjectNode content = mapper.createObjectNode();
            content.put("type", "string");
            props.set("content", content);
            schema.set("properties", props);
            schema.set("required", mapper.createArrayNode().add("content"));
            return schema;
        }

        @Override
        public ExecutionResult executeJsonToolUse(JsonNode args, UUID userId, UUID sessionId, ChatEventSink sink) {
            return ExecutionResult.text("{\"ok\":true}");
        }
    }
}
