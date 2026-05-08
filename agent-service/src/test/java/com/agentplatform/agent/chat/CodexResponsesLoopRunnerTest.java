package com.agentplatform.agent.chat;

import com.agentplatform.agent.ai.ChatEventSink;
import com.agentplatform.agent.ai.ConfiguredProvider;
import com.agentplatform.agent.ai.ExecutionResult;
import com.agentplatform.agent.ai.ResolvedTools;
import com.agentplatform.agent.ai.ServerToolCallback;
import com.agentplatform.agent.ai.ServerToolRegistry;
import com.agentplatform.agent.ai.SkillLoadCallback;
import com.agentplatform.agent.ai.SkillRegistry;
import com.agentplatform.agent.client.InternalChatFeignClient;
import com.agentplatform.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CodexResponsesLoopRunnerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void buildToolsIncludesNativeWebSearchWhenEnabled() throws Exception {
        CodexResponsesLoopRunner runner = runner(memory(true));

        ArrayNode tools = invokeBuildTools(runner);

        assertThat(tools)
                .anySatisfy(tool -> assertThat(tool.path("type").asText()).isEqualTo("web_search"));
    }

    @Test
    void buildToolsOmitsNativeWebSearchWhenDisabled() throws Exception {
        CodexResponsesLoopRunner runner = runner(memory(false));

        ArrayNode tools = invokeBuildTools(runner);

        assertThat(tools)
                .noneSatisfy(tool -> assertThat(tool.path("type").asText()).isEqualTo("web_search"));
    }

    @Test
    void runConvertsToolThrowablesToFunctionOutput() throws Exception {
        ObjectNode first = mapper.createObjectNode();
        ArrayNode firstOutput = mapper.createArrayNode();
        ObjectNode call = mapper.createObjectNode();
        call.put("type", "function_call");
        call.put("name", "boom_tool");
        call.put("call_id", "call_1");
        call.put("arguments", "{\"value\":\"x\"}");
        firstOutput.add(call);
        first.set("output", firstOutput);

        ObjectNode second = mapper.createObjectNode();
        second.put("output_text", "recovered");

        try (ResponsesStubServer server = new ResponsesStubServer(mapper, List.of(first, second))) {
            CodexResponsesLoopRunner runner = runner(
                    memory(false),
                    new ServerToolRegistry(List.of(new ThrowingTool()), mapper),
                    WebClient.builder());

            RunResult result = runner.run(
                    new ConfiguredProvider("test", "codex-responses", null, server.baseUrl(), "token", "model"),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new ResolvedTools(List.of(), Map.of()),
                    "system",
                    List.of(),
                    "use tool",
                    event -> {
                    },
                    new SseEmitter());

            assertThat(result.assistantText()).isEqualTo("recovered");
            JsonNode replayedInput = server.requests().get(1).path("input");
            assertThat(replayedInput)
                    .anySatisfy(item -> {
                        assertThat(item.path("type").asText()).isEqualTo("function_call_output");
                        assertThat(item.path("call_id").asText()).isEqualTo("call_1");
                        JsonNode output = mapper.readTree(item.path("output").asText());
                        assertThat(output.path("error").asText())
                                .isEqualTo("tool execution failed: unexpected boom");
                    });
        }
    }

    private CodexResponsesLoopRunner runner(AgentProperties.Memory memory) {
        return runner(memory, new ServerToolRegistry(List.of(), mapper), WebClient.builder());
    }

    private CodexResponsesLoopRunner runner(AgentProperties.Memory memory,
                                            ServerToolRegistry serverToolRegistry,
                                            WebClient.Builder webClientBuilder) {
        AgentProperties.Agent agent = new AgentProperties.Agent(
                "photos.list_recent",
                "{\"limit\":5}",
                35_000,
                "http://device-hub-service:8080",
                4096,
                24,
                List.of(),
                memory,
                null);
        AgentProperties props = new AgentProperties(new AgentProperties.Jwt("secret", "issuer"), agent);
        SkillLoadCallback skillLoad = new SkillLoadCallback(new SkillRegistry(mock(InternalChatFeignClient.class)), mapper);
        return new CodexResponsesLoopRunner(
                mapper,
                props,
                skillLoad,
                serverToolRegistry,
                webClientBuilder);
    }

    private AgentProperties.Memory memory(boolean enableWebSearch) {
        return new AgentProperties.Memory(
                null,
                0,
                0,
                null,
                null,
                0,
                null,
                0,
                enableWebSearch,
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

    private ArrayNode invokeBuildTools(CodexResponsesLoopRunner runner) throws Exception {
        Method method = CodexResponsesLoopRunner.class.getDeclaredMethod(
                "buildTools",
                com.agentplatform.agent.ai.ResolvedTools.class);
        method.setAccessible(true);
        Object value = method.invoke(runner, new com.agentplatform.agent.ai.ResolvedTools(List.of(), java.util.Map.of()));
        assertThat(value).isInstanceOf(ArrayNode.class);
        ArrayNode tools = (ArrayNode) value;
        for (JsonNode tool : tools) {
            assertThat(tool.path("type").asText()).isNotBlank();
        }
        return tools;
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

    private static class ResponsesStubServer implements AutoCloseable {

        private final ObjectMapper mapper;
        private final List<JsonNode> responses;
        private final List<JsonNode> requests = new ArrayList<>();
        private final HttpServer server;
        private int index;

        ResponsesStubServer(ObjectMapper mapper, List<JsonNode> responses) throws IOException {
            this.mapper = mapper;
            this.responses = responses;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/v1/responses", this::handle);
            this.server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<JsonNode> requests() {
            return requests;
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] body = exchange.getRequestBody().readAllBytes();
            requests.add(mapper.readTree(body));
            byte[] response = responses.get(index++).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
