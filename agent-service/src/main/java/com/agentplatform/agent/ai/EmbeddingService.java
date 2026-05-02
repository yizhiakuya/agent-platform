package com.agentplatform.agent.ai;

import com.agentplatform.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Embeds plain text via an OpenAI-compatible {@code POST /v1/embeddings}
 * endpoint. Used by:
 * <ul>
 *   <li>{@link com.agentplatform.agent.chat.ChatService} for memory recall
 *       (embed user message → vector search chat-service).</li>
 *   <li>{@link MemoryExtractor} for storing extracted facts.</li>
 * </ul>
 *
 * <p>v0 reuses the first configured chat provider's {@code baseUrl + apiKey};
 * a sub2api-style proxy speaks the OpenAI embeddings format even when the
 * primary chat protocol is Anthropic. If your primary provider doesn't expose
 * embeddings, point a future {@code MEMORY_EMBEDDING_*} env vars at a separate
 * provider — left as a TODO for after B1.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final WebClient webClient;
    private final AgentProperties props;
    private final String apiKey;
    private final String embeddingModel;
    private final int embeddingDim;

    public EmbeddingService(WebClient.Builder defaultWebClientBuilder, AgentProperties props) {
        this.props = props;
        AgentProperties.Provider primary = primaryProvider(props);
        String baseUrl = primary == null || primary.baseUrl() == null || primary.baseUrl().isBlank()
                ? "https://api.openai.com"
                : primary.baseUrl();
        this.apiKey = primary == null ? "" : (primary.apiKey() == null ? "" : primary.apiKey());
        this.embeddingModel = props.agent().memory().embeddingModel();
        this.embeddingDim = props.agent().memory().embeddingDim();
        this.webClient = defaultWebClientBuilder
                .baseUrl(baseUrl)
                .build();
        log.info("[embed] EmbeddingService configured: baseUrl={} model={} dim={}",
                baseUrl, embeddingModel, embeddingDim);
    }

    private static AgentProperties.Provider primaryProvider(AgentProperties props) {
        List<AgentProperties.Provider> all = props.agent().providers();
        if (all == null || all.isEmpty()) return null;
        return all.get(0);
    }

    /**
     * Embeds {@code text} and returns the raw {@code float[]} vector.
     * Throws {@link IllegalStateException} on transport / parse failure so
     * callers can decide to swallow (recall path) or propagate.
     */
    public float[] embed(String text) {
        if (text == null) text = "";
        if (apiKey.isBlank()) {
            throw new IllegalStateException("Embedding provider apiKey is blank — set ANTHROPIC_API_KEY or configure providers[0].apiKey");
        }
        try {
            JsonNode resp = webClient.post()
                    .uri("/v1/embeddings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("input", text, "model", embeddingModel))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(20));
            if (resp == null) {
                throw new IllegalStateException("Empty embedding response");
            }
            JsonNode data = resp.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new IllegalStateException("Bad embedding response: " + resp);
            }
            JsonNode arr = data.get(0).path("embedding");
            if (!arr.isArray()) {
                throw new IllegalStateException("Bad embedding response (no embedding array): " + resp);
            }
            float[] out = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                out[i] = (float) arr.get(i).asDouble();
            }
            if (embeddingDim > 0 && out.length != embeddingDim) {
                log.warn("[embed] embedding dim {} != configured {} — chat-service insert may fail", out.length, embeddingDim);
            }
            return out;
        } catch (RuntimeException e) {
            throw new IllegalStateException("embed(" + embeddingModel + ") failed: " + e.getMessage(), e);
        }
    }

    public int dim() {
        return embeddingDim;
    }
}
