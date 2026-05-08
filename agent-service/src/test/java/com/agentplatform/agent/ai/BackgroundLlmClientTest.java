package com.agentplatform.agent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackgroundLlmClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BackgroundLlmClient client = new BackgroundLlmClient(mapper, WebClient.builder());

    @Test
    void choosePlanKeepsClaudeExtractorOnAnthropicWhenAvailable() {
        ConfiguredProvider codex = new ConfiguredProvider(
                "codex",
                "codex-responses",
                null,
                "http://127.0.0.1",
                "token",
                "gpt-5.5");
        ConfiguredProvider anthropic = new ConfiguredProvider(
                "anthropic",
                "anthropic-messages",
                null,
                "http://127.0.0.1",
                "token",
                "claude-sonnet-4-6");

        BackgroundLlmClient.CompletionPlan plan = client.choosePlan(
                List.of(codex, anthropic),
                "claude-haiku-4-5");

        assertThat(plan).isNotNull();
        assertThat(plan.provider()).isSameAs(anthropic);
        assertThat(plan.model()).isEqualTo("claude-haiku-4-5");
    }

    @Test
    void choosePlanFallsBackToCodexProviderModelForClaudeExtractorWhenCodexOnly() {
        ConfiguredProvider codex = new ConfiguredProvider(
                "codex",
                "codex-responses",
                null,
                "http://127.0.0.1",
                "token",
                "gpt-5.5");

        BackgroundLlmClient.CompletionPlan plan = client.choosePlan(
                List.of(codex),
                "claude-haiku-4-5");

        assertThat(plan).isNotNull();
        assertThat(plan.provider()).isSameAs(codex);
        assertThat(plan.model()).isEqualTo("gpt-5.5");
    }

    @Test
    void completeCallsResponsesEndpointForCodexProvider() throws Exception {
        ObjectNode response = mapper.createObjectNode();
        response.put("output_text", "[]");

        try (ResponsesStubServer server = new ResponsesStubServer(mapper, response)) {
            ConfiguredProvider codex = new ConfiguredProvider(
                    "codex",
                    "codex-responses",
                    null,
                    server.baseUrl(),
                    "token",
                    "gpt-5.5");

            String text = client.complete(new BackgroundLlmClient.CompletionPlan(codex, "gpt-5.5"),
                    "extract facts", 128L);

            assertThat(text).isEqualTo("[]");
            assertThat(server.requests()).hasSize(1);
            JsonNode request = server.requests().getFirst();
            assertThat(request.path("model").asText()).isEqualTo("gpt-5.5");
            assertThat(request.path("max_output_tokens").asLong()).isEqualTo(128L);
            JsonNode input = request.path("input");
            assertThat(input.isArray()).isTrue();
            assertThat(input.size()).isEqualTo(1);
            JsonNode item = input.get(0);
            assertThat(item.path("role").asText()).isEqualTo("user");
            assertThat(item.path("content").asText()).isEqualTo("extract facts");
        }
    }

    @Test
    void completeExtractsTextFromResponsesOutputContent() throws Exception {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode output = mapper.createArrayNode();
        ObjectNode message = mapper.createObjectNode();
        ArrayNode content = mapper.createArrayNode();
        ObjectNode block = mapper.createObjectNode();
        block.put("type", "output_text");
        block.put("text", "summary");
        content.add(block);
        message.set("content", content);
        output.add(message);
        response.set("output", output);

        try (ResponsesStubServer server = new ResponsesStubServer(mapper, response)) {
            ConfiguredProvider codex = new ConfiguredProvider(
                    "codex",
                    "codex-responses",
                    null,
                    server.baseUrl(),
                    "token",
                    "gpt-5.5");

            String text = client.complete(new BackgroundLlmClient.CompletionPlan(codex, "gpt-5.5"),
                    "summarize", 64L);

            assertThat(text).isEqualTo("summary");
        }
    }

    private static class ResponsesStubServer implements AutoCloseable {

        private final ObjectMapper mapper;
        private final JsonNode response;
        private final List<JsonNode> requests = new ArrayList<>();
        private final HttpServer server;

        ResponsesStubServer(ObjectMapper mapper, JsonNode response) throws IOException {
            this.mapper = mapper;
            this.response = response;
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
            byte[] responseBytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
