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
import java.io.OutputStream;
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
    void buildToolsOmitsResponsesUnsupportedRootSchemaKeywords() throws Exception {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        ObjectNode id = mapper.createObjectNode();
        id.put("type", "string");
        props.set("id", id);
        schema.set("properties", props);
        schema.set("anyOf", mapper.createArrayNode().add(mapper.createObjectNode().set(
                "required", mapper.createArrayNode().add("id"))));
        schema.set("oneOf", mapper.createArrayNode().add(mapper.createObjectNode()));
        schema.set("allOf", mapper.createArrayNode().add(mapper.createObjectNode()));
        schema.set("enum", mapper.createArrayNode().add("x"));
        schema.set("not", mapper.createObjectNode());
        CodexResponsesLoopRunner runner = runner(
                memory(false),
                new ServerToolRegistry(List.of(new SchemaTool(schema)), mapper),
                WebClient.builder());

        ArrayNode tools = invokeBuildTools(runner);

        JsonNode parameters = firstTool(tools, "schema_tool").path("parameters");
        assertThat(parameters.path("type").asText()).isEqualTo("object");
        assertThat(parameters.path("properties").has("id")).isTrue();
        assertThat(parameters.has("anyOf")).isFalse();
        assertThat(parameters.has("oneOf")).isFalse();
        assertThat(parameters.has("allOf")).isFalse();
        assertThat(parameters.has("enum")).isFalse();
        assertThat(parameters.has("not")).isFalse();
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

        try (ResponsesStubServer server = ResponsesStubServer.fromResponses(mapper, List.of(first, second))) {
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

    @Test
    void runStreamsOutputTextDeltasToSink() throws Exception {
        ObjectNode completed = mapper.createObjectNode();
        completed.put("id", "resp_1");
        completed.put("status", "completed");
        ArrayNode output = mapper.createArrayNode();
        ObjectNode message = mapper.createObjectNode();
        message.put("type", "message");
        ArrayNode content = mapper.createArrayNode();
        ObjectNode text = mapper.createObjectNode();
        text.put("type", "output_text");
        text.put("text", "hello world");
        content.add(text);
        message.set("content", content);
        output.add(message);
        completed.set("output", output);

        List<List<JsonNode>> streams = List.of(List.of(
                streamEvent("response.output_text.delta", "delta", "hello "),
                streamEvent("response.output_text.delta", "delta", "world"),
                streamEvent("response.completed", "response", completed)
        ));

        try (ResponsesStubServer server = ResponsesStubServer.fromStreams(mapper, streams)) {
            CodexResponsesLoopRunner runner = runner(memory(false));
            List<SseEvent> events = new ArrayList<>();

            RunResult result = runner.run(
                    new ConfiguredProvider("test", "codex-responses", null, server.baseUrl(), "token", "model"),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new ResolvedTools(List.of(), Map.of()),
                    "system",
                    List.of(),
                    "say hello",
                    events::add,
                    new SseEmitter());

            assertThat(result.assistantText()).isEqualTo("hello world");
            assertThat(events)
                    .extracting(SseEvent::type)
                    .containsExactly("assistant_message", "assistant_message");
            assertThat(events)
                    .extracting(event -> event.data().path("content").asText())
                    .containsExactly("hello ", "world");
            assertThat(server.requests().getFirst().path("stream").asBoolean()).isTrue();
        }
    }

    @Test
    void runAcceptsResponseDoneAsTerminalStreamEvent() throws Exception {
        ObjectNode completed = completedTextResponse("done text");
        List<List<JsonNode>> streams = List.of(List.of(
                streamEvent("response.output_text.delta", "delta", "done text"),
                streamEvent("response.done", "response", completed)
        ));

        try (ResponsesStubServer server = ResponsesStubServer.fromStreams(mapper, streams)) {
            CodexResponsesLoopRunner runner = runner(memory(false));

            RunResult result = runner.run(
                    new ConfiguredProvider("test", "codex-responses", null, server.baseUrl(), "token", "model"),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new ResolvedTools(List.of(), Map.of()),
                    "system",
                    List.of(),
                    "say done",
                    event -> {
                    },
                    new SseEmitter());

            assertThat(result.assistantText()).isEqualTo("done text");
        }
    }

    @Test
    void runPersistsTextFromOutputItemWhenCompletedResponseOutputIsEmpty() throws Exception {
        ObjectNode completed = mapper.createObjectNode();
        completed.put("id", "resp_empty_output");
        completed.put("status", "completed");
        completed.set("output", mapper.createArrayNode());

        ObjectNode message = completedMessageItem("streamed text");
        List<List<JsonNode>> streams = List.of(List.of(
                streamEvent("response.output_text.delta", "delta", "streamed text"),
                streamEvent("response.output_item.done", "item", message),
                streamEvent("response.completed", "response", completed)
        ));

        try (ResponsesStubServer server = ResponsesStubServer.fromStreams(mapper, streams)) {
            CodexResponsesLoopRunner runner = runner(memory(false));

            RunResult result = runner.run(
                    new ConfiguredProvider("test", "codex-responses", null, server.baseUrl(), "token", "model"),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new ResolvedTools(List.of(), Map.of()),
                    "system",
                    List.of(),
                    "say text",
                    event -> {
                    },
                    new SseEmitter());

            assertThat(result.assistantText()).isEqualTo("streamed text");
        }
    }

    private CodexResponsesLoopRunner runner(AgentProperties.Memory memory) {
        return runner(memory, new ServerToolRegistry(List.of(), mapper), WebClient.builder());
    }

    private CodexResponsesLoopRunner runner(AgentProperties.Memory memory,
                                            ServerToolRegistry serverToolRegistry,
                                            WebClient.Builder webClientBuilder) {
        AgentProperties.Agent agent = new AgentProperties.Agent(
                35_000,
                "http://device-hub-service:8080",
                4096,
                24,
                24,
                10,
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

    private JsonNode firstTool(ArrayNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asText())) {
                return tool;
            }
        }
        throw new AssertionError("tool not found: " + name);
    }

    private static class SchemaTool implements ServerToolCallback {
        private final JsonNode schema;

        private SchemaTool(JsonNode schema) {
            this.schema = schema;
        }

        @Override
        public String name() {
            return "schema_tool";
        }

        @Override
        public String description() {
            return "Schema test tool";
        }

        @Override
        public JsonNode schema() {
            return schema;
        }

        @Override
        public ExecutionResult executeJsonToolUse(JsonNode args, UUID userId, UUID sessionId, ChatEventSink sink) {
            return ExecutionResult.text("{}");
        }
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
        private final List<List<JsonNode>> streams;
        private final List<JsonNode> requests = new ArrayList<>();
        private final HttpServer server;
        private int index;

        static ResponsesStubServer fromResponses(ObjectMapper mapper, List<JsonNode> responses) throws IOException {
            List<List<JsonNode>> streams = responses.stream()
                    .map(response -> List.<JsonNode>of(streamEvent("response.completed", "response", response)))
                    .toList();
            return new ResponsesStubServer(mapper, streams);
        }

        static ResponsesStubServer fromStreams(ObjectMapper mapper, List<List<JsonNode>> streams) throws IOException {
            return new ResponsesStubServer(mapper, streams);
        }

        private ResponsesStubServer(ObjectMapper mapper, List<List<JsonNode>> streams) throws IOException {
            this.mapper = mapper;
            this.streams = streams;
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
            List<JsonNode> events = streams.get(index++);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (JsonNode event : events) {
                    out.write(("data: " + event + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static ObjectNode streamEvent(String type, String field, JsonNode value) {
        ObjectNode event = new ObjectMapper().createObjectNode();
        event.put("type", type);
        event.set(field, value);
        return event;
    }

    private static ObjectNode streamEvent(String type, String field, String value) {
        ObjectNode event = new ObjectMapper().createObjectNode();
        event.put("type", type);
        event.put(field, value);
        return event;
    }

    private ObjectNode completedTextResponse(String value) {
        ObjectNode completed = mapper.createObjectNode();
        completed.put("id", "resp_1");
        completed.put("status", "completed");
        ArrayNode output = mapper.createArrayNode();
        output.add(completedMessageItem(value));
        completed.set("output", output);
        return completed;
    }

    private ObjectNode completedMessageItem(String value) {
        ObjectNode message = mapper.createObjectNode();
        message.put("type", "message");
        message.put("status", "completed");
        message.put("role", "assistant");
        ArrayNode content = mapper.createArrayNode();
        ObjectNode text = mapper.createObjectNode();
        text.put("type", "output_text");
        text.put("text", value);
        content.add(text);
        message.set("content", content);
        return message;
    }
}
