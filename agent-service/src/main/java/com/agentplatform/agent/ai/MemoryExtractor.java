package com.agentplatform.agent.ai;

import com.agentplatform.agent.client.InternalChatFeignClient;
import com.agentplatform.agent.config.AgentProperties;
import com.agentplatform.api.chat.SaveFactRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Background fact extractor: after every assistant turn, asks the LLM to pull
 * any durable user-specific facts/preferences/rules out of the (user, assistant)
 * exchange, embeds each fact, and persists it to chat-service's memory store.
 *
 * <p>Runs entirely off the SSE response path on the {@code chatExecutor} thread
 * pool — the user never waits for it, and any failure is logged at WARN and
 * dropped (memory is a best-effort enrichment, not a contract).
 *
 * <p>Provider routing is delegated through the LangChain4j
 * {@link BackgroundFactExtractor} service, backed by the configured provider
 * pool. Anthropic deployments can keep the cheap extractor model, and Codex-only
 * deployments use their configured Responses provider instead of disabling memory.
 *
 */
@Service
public class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);

    private final EmbeddingService embeddingService;
    private final InternalChatFeignClient chatFeign;
    private final ExecutorService chatExecutor;
    private final ObjectMapper mapper;
    private final AgentProperties props;
    private final BackgroundFactExtractor factExtractor;

    // Per-session ring of (USER, ASSISTANT) turns waiting for the LLM extractor
    // to chew on. We flush in batches to cut the per-turn sub2api request count
    // — each chat used to spawn (1 stream + 1 embed + 1 extract); now it's
    // (1 stream + 1 embed) most turns and (+1 extract) only every Nth turn.
    private final Map<UUID, List<String>> pending = new ConcurrentHashMap<>();

    public MemoryExtractor(EmbeddingService embeddingService,
                           InternalChatFeignClient chatFeign,
                           ExecutorService chatExecutor,
                           ObjectMapper mapper,
                           AgentProperties props,
                           BackgroundFactExtractor factExtractor) {
        this.embeddingService = embeddingService;
        this.chatFeign = chatFeign;
        this.chatExecutor = chatExecutor;
        this.mapper = mapper;
        this.props = props;
        this.factExtractor = factExtractor;
    }

    /**
     * Buffer a turn for batched extraction. Once {@code factBatchSize} turns
     * have accumulated for a session, drains them and runs the extractor LLM
     * once over the whole batch.
     *
     * <p>Open issue: turns left in the buffer when the user walks away never
     * get extracted — memory is best-effort, so we tolerate this. A scheduled
     * idle-flush could close the gap later.
     */
    public void extractAsync(UUID userId, UUID sessionId, String userMsg, String assistantMsg) {
        if (userId == null || sessionId == null) return;
        if ((userMsg == null || userMsg.isBlank()) && (assistantMsg == null || assistantMsg.isBlank())) return;

        int batchSize = props.agent().memory().factBatchSize();
        String turn = "USER: %s%nASSISTANT: %s".formatted(safe(userMsg), safe(assistantMsg));

        List<String> drained = null;
        synchronized (pending) {
            List<String> list = pending.computeIfAbsent(sessionId, k -> new ArrayList<>());
            list.add(turn);
            if (list.size() >= batchSize) {
                drained = new ArrayList<>(list);
                pending.remove(sessionId);
            }
        }
        if (drained != null) {
            List<String> batch = drained;
            chatExecutor.execute(() -> runExtractionBatch(userId, sessionId, batch));
        }
    }

    private void runExtractionBatch(UUID userId, UUID sessionId, List<String> turns) {
        try {
            String reply = extractFacts(userId, batchBody(turns));
            JsonNode node = parseFactArray(userId, reply);
            if (node == null) return;

            int saved = 0;
            for (JsonNode item : node) {
                String kind = normalizeKind(item.path("kind").asText("fact").trim());
                String content = item.path("content").asText("").trim();
                if (content.isBlank()) continue;
                if (saveFact(userId, kind, content)) {
                    saved++;
                }
            }
            if (saved > 0) {
                log.info("[memory-extract] saved {} fact(s) for user {} session {} (batch of {} turns)",
                        saved, userId, sessionId, turns.size());
            }
        } catch (Exception ex) {
            log.warn("[memory-extract] batch extraction failed for user {}: {}", userId, ex.getMessage());
        }
    }

    private String batchBody(List<String> turns) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < turns.size(); i++) {
            body.append("Exchange %d:%n%s%n%n".formatted(i + 1, turns.get(i)));
        }
        return body.toString();
    }

    private String extractFacts(UUID userId, String body) {
        try {
            return factExtractor.extractFacts(body);
        } catch (Exception e) {
            log.warn("[memory-extract] fact extraction LLM call failed for user {}: {}",
                    userId, e.getMessage());
            return null;
        }
    }

    private JsonNode parseFactArray(UUID userId, String reply) throws com.fasterxml.jackson.core.JsonProcessingException {
        if (reply == null || reply.isBlank()) return null;
        String arr = sliceFirstArray(reply);
        if (arr == null) {
            log.debug("[memory-extract] no JSON array in extractor reply for user {}", userId);
            return null;
        }
        JsonNode node = mapper.readTree(arr);
        return node.isArray() && !node.isEmpty() ? node : null;
    }

    private boolean saveFact(UUID userId, String kind, String content) {
        try {
            float[] embedding = embeddingService.embed(content);
            chatFeign.saveFact(new SaveFactRequest(userId, kind, content, null, embedding));
            return true;
        } catch (Exception e) {
            log.warn("[memory-extract] save fact failed for user {}: {}", userId, e.getMessage());
            return false;
        }
    }

    private static String normalizeKind(String kind) {
        return kind.equals("preference") || kind.equals("rule") ? kind : "fact";
    }

    /** Pull the first {@code [...]} block out of a possibly-wrapped LLM reply. */
    private static String sliceFirstArray(String s) {
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start < 0 || end < 0 || end <= start) return null;
        return s.substring(start, end + 1);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
