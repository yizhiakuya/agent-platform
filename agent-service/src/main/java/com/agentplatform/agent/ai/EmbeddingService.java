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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Embeds plain text via an OpenAI-compatible {@code POST /v1/embeddings}
 * endpoint. Used by:
 * <ul>
 *   <li>{@link com.agentplatform.agent.chat.ChatService} for memory recall
 *       (embed user message → vector search chat-service).</li>
 *   <li>{@link MemoryExtractor} for storing extracted facts.</li>
 * </ul>
 *
 * <p>Provider selection:
 * <ol>
 *   <li>If {@code agent.memory.embeddingBaseUrl} + {@code embeddingApiKey} are
 *       set, use them. This is the production path — anthropic-only relays
 *       (sub2api) don't expose {@code /v1/embeddings}, point at
 *       {@code https://api.openai.com} or a self-hosted TEI / ollama gateway.</li>
 *   <li>Otherwise fall back to the first configured chat provider's baseUrl +
 *       apiKey. Convenient for dev when the chat relay also speaks OpenAI
 *       embeddings.</li>
 * </ol>
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final WebClient webClient;
    private final AgentProperties props;
    private final String apiKey;
    private final String embeddingModel;
    private final int embeddingDim;
    private final String embeddingTask;  // null/blank = omit (OpenAI doesn't take it; Jina v3 requires it)

    // LRU cache by raw input text. Most embeddings come from (a) the current
    // user message during memory recall and (b) extracted fact contents during
    // store. Both are stable strings — same text → same vector — so caching
    // saves real money: avoids re-paying $0.02/M tokens to OpenAI for the same
    // input within minutes. Size 256 covers ~30-60 minutes of normal traffic.
    private static final int LRU_MAX = 256;
    private final Map<String, float[]> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, float[]>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                    return size() > LRU_MAX;
                }
            });
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public EmbeddingService(WebClient.Builder defaultWebClientBuilder, AgentProperties props) {
        this.props = props;
        AgentProperties.Memory mem = props.agent().memory();
        String configuredBase = mem.embeddingBaseUrl();
        String configuredKey = mem.embeddingApiKey();
        boolean useDedicated = configuredBase != null && !configuredBase.isBlank()
                && configuredKey != null && !configuredKey.isBlank();

        String baseUrl;
        String source;
        if (useDedicated) {
            baseUrl = configuredBase;
            this.apiKey = configuredKey;
            source = "dedicated";
        } else {
            AgentProperties.Provider primary = primaryProvider(props);
            baseUrl = primary == null || primary.baseUrl() == null || primary.baseUrl().isBlank()
                    ? "https://api.openai.com"
                    : primary.baseUrl();
            this.apiKey = primary == null ? "" : (primary.apiKey() == null ? "" : primary.apiKey());
            source = "fallback-to-chat-provider";
        }

        this.embeddingModel = mem.embeddingModel();
        this.embeddingDim = mem.embeddingDim();
        this.embeddingTask = mem.embeddingTask();
        this.webClient = defaultWebClientBuilder
                .baseUrl(baseUrl)
                .build();
        log.info("[embed] EmbeddingService configured: baseUrl={} model={} dim={} task={} source={}",
                baseUrl, embeddingModel, embeddingDim,
                embeddingTask == null || embeddingTask.isBlank() ? "(none)" : embeddingTask,
                source);
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
        // LRU lookup before hitting the network.
        float[] cached = cache.get(text);
        if (cached != null) {
            long h = hits.incrementAndGet();
            if ((h & 31) == 0) {
                log.info("[embed] LRU stats hits={} misses={} size={}", h, misses.get(), cache.size());
            }
            return cached;
        }
        try {
            // Jina v3 requires `task` (retrieval.passage / retrieval.query /
            // text-matching / classification / separation); OpenAI rejects it.
            // Build the body conditionally based on whether one is configured.
            Map<String, Object> body;
            if (embeddingTask == null || embeddingTask.isBlank()) {
                body = Map.of("input", text, "model", embeddingModel);
            } else {
                body = Map.of("input", text, "model", embeddingModel, "task", embeddingTask);
            }
            JsonNode resp = webClient.post()
                    .uri("/v1/embeddings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
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
            cache.put(text, out);
            misses.incrementAndGet();
            return out;
        } catch (RuntimeException e) {
            throw new IllegalStateException("embed(" + embeddingModel + ") failed: " + e.getMessage(), e);
        }
    }

    public int dim() {
        return embeddingDim;
    }
}
