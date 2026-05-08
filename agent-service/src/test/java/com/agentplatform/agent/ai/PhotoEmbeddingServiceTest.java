package com.agentplatform.agent.ai;

import com.agentplatform.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class PhotoEmbeddingServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private volatile String requestBody;
    private volatile String authorization;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void embedTextSendsJinaClipInputDoc() throws Exception {
        PhotoEmbeddingService service = service();

        float[] embedding = service.embedText("cat photo");

        assertArrayEquals(new float[]{0.25f, 0.75f}, embedding);
        JsonNode body = mapper.readTree(requestBody);
        assertEquals("Bearer test-key", authorization);
        assertEquals("jina-clip-v2", body.path("model").asText());
        assertEquals("cat photo", body.path("input").get(0).path("text").asText());
        assertEquals("retrieval.query", body.path("task").asText());
        assertFalse(body.has("image"));
    }

    @Test
    void embedImageSendsImageDocInsideInput() throws Exception {
        PhotoEmbeddingService service = service();

        float[] embedding = service.embedImage("/9j/thumb");

        assertArrayEquals(new float[]{0.25f, 0.75f}, embedding);
        JsonNode body = mapper.readTree(requestBody);
        assertEquals("jina-clip-v2", body.path("model").asText());
        assertEquals("/9j/thumb", body.path("input").get(0).path("image").asText());
        assertFalse(body.has("image"));
        assertFalse(body.has("task"));
    }

    @Test
    void embedImagesSendsSingleBatchRequest() throws Exception {
        PhotoEmbeddingService service = service("""
                {"data":[{"embedding":[0.1,0.2]},{"embedding":[0.3,0.4]}]}
                """);

        List<float[]> embeddings = service.embedImages(List.of("/9j/one", "/9j/two"));

        assertEquals(2, embeddings.size());
        assertArrayEquals(new float[]{0.1f, 0.2f}, embeddings.get(0));
        assertArrayEquals(new float[]{0.3f, 0.4f}, embeddings.get(1));
        JsonNode body = mapper.readTree(requestBody);
        assertEquals(2, body.path("input").size());
        assertEquals("/9j/one", body.path("input").get(0).path("image").asText());
        assertEquals("/9j/two", body.path("input").get(1).path("image").asText());
    }

    @Test
    void embedImagesAcceptsBatchedResponse() throws Exception {
        PhotoEmbeddingService service = service("""
                {"data":[{"embedding":[0.1,0.2]},{"embedding":[0.3,0.4]},{"embedding":[0.5,0.6]}]}
                """);

        List<float[]> embeddings = service.embedImages(List.of("/9j/one", "/9j/two", "/9j/three"));

        assertEquals(3, embeddings.size());
        assertArrayEquals(new float[]{0.5f, 0.6f}, embeddings.get(2));
    }

    @Test
    void localSidecarDoesNotRequireApiKey() throws Exception {
        PhotoEmbeddingService service = service("""
                {"data":[{"embedding":[0.25,0.75]}]}
                """, "");

        float[] embedding = service.embedText("cat photo");

        assertArrayEquals(new float[]{0.25f, 0.75f}, embedding);
        assertNull(authorization);
    }

    private PhotoEmbeddingService service() throws IOException {
        return service("""
                {"data":[{"embedding":[0.25,0.75]}]}
                """);
    }

    private PhotoEmbeddingService service(String responseBody) throws IOException {
        return service(responseBody, "test-key");
    }

    private PhotoEmbeddingService service(String responseBody, String apiKey) throws IOException {
        String baseUrl = startServer();
        this.embeddingResponse = responseBody;
        AgentProperties.Photos photos = new AgentProperties.Photos(
                "jina-clip-v2",
                baseUrl,
                apiKey,
                2,
                "retrieval.query",
                null,
                "image",
                true,
                true,
                8,
                0.18,
                25,
                45);
        AgentProperties.Agent agent = new AgentProperties.Agent(
                null,
                null,
                0,
                null,
                4096,
                24,
                List.of(),
                null,
                photos);
        AgentProperties props = new AgentProperties(new AgentProperties.Jwt("secret", "issuer"), agent);
        return new PhotoEmbeddingService(WebClient.builder(), props);
    }

    private String embeddingResponse;

    private String startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] response = embeddingResponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
