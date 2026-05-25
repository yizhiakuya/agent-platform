package com.agentplatform.agent.ai;

import com.agentplatform.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multimodal embedding client for the server-side photo index.
 *
 * <p>Expected contract: text queries and thumbnail images return vectors in the
 * same space. The default body matches Jina CLIP v2's OpenAI-compatible endpoint:
 * {@code {model, input: [{text}], task?}} for text and
 * {@code {model, input: [{image}], task?}} for image thumbnails.
 */
@Service
public class PhotoEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(PhotoEmbeddingService.class);

    private final WebClient webClient;
    private final String apiKey;
    private final boolean requiresApiKey;
    private final String model;
    private final int dim;
    private final String textTask;
    private final String imageTask;
    private final String inputImageField;
    private final boolean enabled;
    private final int requestTimeoutSeconds;

    public PhotoEmbeddingService(WebClient.Builder defaultWebClientBuilder, AgentProperties props) {
        AgentProperties.Photos photos = photos(props);
        AgentProperties.Memory memory = memory(props);
        AgentProperties.Provider primary = primaryProvider(props);
        String baseUrl = resolveBaseUrl(photos, memory, primary);
        boolean authRequired = !isLocalEndpoint(baseUrl);

        this.model = photos == null ? "clip-ViT-B-32-multilingual-v1" : photos.embeddingModel();
        this.dim = photos == null ? 1024 : photos.embeddingDim();
        this.textTask = photos == null ? null : photos.textTask();
        this.imageTask = photos == null ? null : photos.imageTask();
        this.inputImageField = photos == null ? "image" : photos.inputImageField();
        this.enabled = photos == null || Boolean.TRUE.equals(photos.enabled());
        this.requestTimeoutSeconds = photos == null ? 180 : Math.max(1, photos.requestTimeoutSeconds());
        this.requiresApiKey = authRequired;
        this.apiKey = resolveApiKey(authRequired, photos, memory, primary);
        ExchangeStrategies exchangeStrategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
        this.webClient = defaultWebClientBuilder
                .clone()
                .baseUrl(baseUrl)
                .exchangeStrategies(exchangeStrategies)
                .build();
        String authState;
        if (apiKey.isBlank()) {
            authState = requiresApiKey ? "missing" : "none";
        } else {
            authState = "bearer";
        }
        log.info("[photo-embed] configured enabled={} baseUrl={} model={} dim={} textTask={} imageTask={} imageField={} timeoutSeconds={} auth={}",
                enabled, baseUrl, model, dim, textTask, imageTask, inputImageField, requestTimeoutSeconds,
                authState);
    }

    public float[] embedText(String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", List.of(Map.of("text", text == null ? "" : text)));
        putIfPresent(body, "task", textTask);
        return call(body);
    }

    public float[] embedImage(String thumbB64) {
        return embedImages(List.of(thumbB64)).getFirst();
    }

    public List<float[]> embedImages(List<String> thumbB64s) {
        if (thumbB64s == null || thumbB64s.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> inputs = thumbB64s.stream()
                .map(thumbB64 -> {
                    if (isBlank(thumbB64)) {
                        throw new IllegalArgumentException("thumbB64 is required");
                    }
                    return Map.of(inputImageField, thumbB64);
                })
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", inputs);
        putIfPresent(body, "task", imageTask);
        return callMany(body);
    }

    private float[] parseEmbedding(JsonNode item, JsonNode fullResponse) {
        JsonNode arr = item.path("embedding");
        if (!arr.isArray()) {
            throw new IllegalStateException("bad embedding response (no embedding array): " + fullResponse);
        }
        float[] out = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            out[i] = (float) arr.get(i).asDouble();
        }
        if (dim > 0 && out.length != dim) {
            log.warn("[photo-embed] embedding dim {} != configured {}", out.length, dim);
        }
        return out;
    }

    private List<float[]> callMany(Map<String, Object> body) {
        JsonNode resp = request(body);
        JsonNode data = resp.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("bad embedding response: " + resp);
        }
        List<float[]> out = new ArrayList<>(data.size());
        for (JsonNode item : data) {
            out.add(parseEmbedding(item, resp));
        }
        return out;
    }

    private float[] call(Map<String, Object> body) {
        JsonNode resp = request(body);
        JsonNode data = resp.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("bad embedding response: " + resp);
        }
        return parseEmbedding(data.get(0), resp);
    }

    private JsonNode request(Map<String, Object> body) {
        if (!enabled) {
            throw new IllegalStateException("photo embedding is disabled");
        }
        if (apiKey.isBlank() && requiresApiKey) {
            throw new IllegalStateException("photo embedding apiKey is blank");
        }
        try {
            JsonNode resp = webClient.post()
                    .uri("/v1/embeddings")
                    .headers(headers -> {
                        if (!apiKey.isBlank()) {
                            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(requestTimeoutSeconds));
            if (resp == null) {
                throw new IllegalStateException("empty embedding response");
            }
            return resp;
        } catch (RuntimeException e) {
            throw new IllegalStateException("photo embed(" + model + ") failed: " + e.getMessage(), e);
        }
    }

    public String model() {
        return model;
    }

    public int dim() {
        return dim;
    }

    public boolean enabled() {
        return enabled;
    }

    private static void putIfPresent(Map<String, Object> body, String key, String value) {
        if (!isBlank(value)) body.put(key, value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isLocalEndpoint(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            String h = host.toLowerCase();
            return h.equals("localhost")
                    || h.equals("::1")
                    || h.startsWith("127.")
                    || h.startsWith("10.")
                    || h.startsWith("192.168.")
                    || h.matches("172\\.(1[6-9]|2\\d|3[0-1])\\..*")
                    || !h.contains(".");
        } catch (Exception e) {
            return false;
        }
    }

    private static AgentProperties.Provider primaryProvider(AgentProperties props) {
        if (props == null || props.agent() == null) return null;
        List<AgentProperties.Provider> all = props.agent().providers();
        if (all == null || all.isEmpty()) return null;
        return all.get(0);
    }

    private static AgentProperties.Photos photos(AgentProperties props) {
        AgentProperties.Agent agent = props == null ? null : props.agent();
        return agent == null ? null : agent.photos();
    }

    private static AgentProperties.Memory memory(AgentProperties props) {
        AgentProperties.Agent agent = props == null ? null : props.agent();
        return agent == null ? null : agent.memory();
    }

    private static String resolveBaseUrl(AgentProperties.Photos photos,
                                         AgentProperties.Memory memory,
                                         AgentProperties.Provider primary) {
        return firstPresent(
                photos == null ? null : photos.embeddingBaseUrl(),
                memory == null ? null : memory.embeddingBaseUrl(),
                primary == null ? null : primary.baseUrl(),
                "https://api.openai.com");
    }

    private static String resolveApiKey(boolean requiresApiKey,
                                        AgentProperties.Photos photos,
                                        AgentProperties.Memory memory,
                                        AgentProperties.Provider primary) {
        if (requiresApiKey) {
            return firstPresent(
                    photos == null ? null : photos.embeddingApiKey(),
                    memory == null ? null : memory.embeddingApiKey(),
                    primary == null ? null : primary.apiKey(),
                    "");
        }
        return firstPresent(
                photos == null ? null : photos.embeddingApiKey(),
                "");
    }

    private static String firstPresent(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }
}
