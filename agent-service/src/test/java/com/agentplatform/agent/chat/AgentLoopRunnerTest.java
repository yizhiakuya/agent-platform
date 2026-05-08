package com.agentplatform.agent.chat;

import com.agentplatform.agent.ai.ChatEventSink;
import com.agentplatform.agent.ai.ExecutionResult;
import com.agentplatform.agent.ai.ResolvedTools;
import com.agentplatform.agent.ai.ServerToolCallback;
import com.agentplatform.agent.ai.ServerToolRegistry;
import com.agentplatform.agent.ai.SkillLoadCallback;
import com.agentplatform.agent.config.AgentProperties;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentLoopRunnerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void executeOneToolUseConvertsServerToolThrowablesToErrorResult() throws Exception {
        AgentLoopRunner runner = new AgentLoopRunner(
                mapper,
                props(),
                mock(SkillLoadCallback.class),
                new ServerToolRegistry(List.of(new ThrowingTool()), mapper));
        ToolUseBlock toolUse = ToolUseBlock.builder()
                .id("toolu_1")
                .name("boom_tool")
                .caller(DirectCaller.builder().type(JsonValue.from("direct")).build())
                .input(JsonValue.from(Map.of("value", "x")))
                .build();
        List<SseEvent> events = new ArrayList<>();

        ExecutionResult result = invokeExecuteOneToolUse(runner, toolUse, events::add);

        assertThat(result.jsonText()).contains("unexpected boom");
        assertThat(events)
                .extracting(SseEvent::type)
                .contains("tool_call_started", "tool_call_result", "error");
        assertThat(events)
                .filteredOn(event -> "tool_call_result".equals(event.type()))
                .anySatisfy(event -> assertThat(event.data().path("result").path("error").path("message").asText())
                        .isEqualTo("unexpected boom"));
    }

    private ExecutionResult invokeExecuteOneToolUse(AgentLoopRunner runner,
                                                    ToolUseBlock toolUse,
                                                    ChatEventSink sink) throws Exception {
        Method method = AgentLoopRunner.class.getDeclaredMethod(
                "executeOneToolUse",
                ToolUseBlock.class,
                ResolvedTools.class,
                UUID.class,
                UUID.class,
                ChatEventSink.class);
        method.setAccessible(true);
        return (ExecutionResult) method.invoke(
                runner,
                toolUse,
                new ResolvedTools(List.of(), Map.of()),
                UUID.randomUUID(),
                UUID.randomUUID(),
                sink);
    }

    private AgentProperties props() {
        return new AgentProperties(
                new AgentProperties.Jwt("secret", "issuer"),
                new AgentProperties.Agent(
                        "photos.list_recent",
                        "{\"limit\":5}",
                        35_000,
                        "http://device-hub-service:8080",
                        4096,
                        24,
                        List.of(),
                        memory(),
                        null));
    }

    private AgentProperties.Memory memory() {
        return new AgentProperties.Memory(
                null,
                0,
                0,
                null,
                null,
                0,
                null,
                0,
                false,
                5,
                null,
                null,
                null,
                12,
                18,
                48_000,
                1_200,
                true);
    }

    private static class ThrowingTool implements ServerToolCallback {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        @Override
        public String name() {
            return "boom_tool";
        }

        @Override
        public String description() {
            return "Throws for test";
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            schema.set("properties", MAPPER.createObjectNode());
            return schema;
        }

        @Override
        public ExecutionResult executeJsonToolUse(JsonNode args, UUID userId, UUID sessionId, ChatEventSink sink) {
            throw new AssertionError("unexpected boom");
        }
    }
}
