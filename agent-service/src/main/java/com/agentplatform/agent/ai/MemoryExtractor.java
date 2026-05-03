package com.agentplatform.agent.ai;

import com.agentplatform.agent.client.InternalChatFeignClient;
import com.agentplatform.agent.config.AgentProperties;
import com.agentplatform.api.chat.SaveFactRequest;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
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
 * <p>Calls the Anthropic SDK directly with {@code factExtractorModel} (cheap
 * Haiku by default) — independent of the main-conversation provider's model
 * choice. v0 fallback: if the first provider's call fails we just drop the
 * batch (best-effort), since fact extraction shouldn't dominate cost or
 * latency budgets.
 */
@Service
public class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);

    private final List<ConfiguredProvider> chatClients;
    private final EmbeddingService embeddingService;
    private final InternalChatFeignClient chatFeign;
    private final ExecutorService chatExecutor;
    private final ObjectMapper mapper;
    private final AgentProperties props;

    // Per-session ring of (USER, ASSISTANT) turns waiting for the LLM extractor
    // to chew on. We flush in batches to cut the per-turn sub2api request count
    // — each chat used to spawn (1 stream + 1 embed + 1 extract); now it's
    // (1 stream + 1 embed) most turns and (+1 extract) only every Nth turn.
    private final Map<UUID, List<String>> pending = new ConcurrentHashMap<>();

    public MemoryExtractor(List<ConfiguredProvider> chatClients,
                           EmbeddingService embeddingService,
                           InternalChatFeignClient chatFeign,
                           ExecutorService chatExecutor,
                           ObjectMapper mapper,
                           AgentProperties props) {
        this.chatClients = chatClients;
        this.embeddingService = embeddingService;
        this.chatFeign = chatFeign;
        this.chatExecutor = chatExecutor;
        this.mapper = mapper;
        this.props = props;
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
        if (chatClients == null || chatClients.isEmpty()) return;
        if (userId == null || sessionId == null) return;
        if ((userMsg == null || userMsg.isBlank()) && (assistantMsg == null || assistantMsg.isBlank())) return;

        int batchSize = props.agent().memory().factBatchSize();
        String turn = "USER: %s\nASSISTANT: %s".formatted(safe(userMsg), safe(assistantMsg));

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
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < turns.size(); i++) {
                body.append("Exchange ").append(i + 1).append(":\n").append(turns.get(i)).append("\n\n");
            }
            String prompt = """
                    Below are %d recent (USER, ASSISTANT) exchanges from the same conversation.
                    Across all of them, extract durable user-specific facts, preferences, or
                    rules worth remembering for future conversations. Combine related observations
                    where it makes sense; skip transient one-off details. Output a JSON array, e.g.
                    [
                      {"kind":"preference","content":"用户喜欢简短中文回答"},
                      {"kind":"fact","content":"用户的小狗叫旺财"}
                    ]
                    Allowed kind values: fact | preference | rule.
                    If nothing worth remembering, output [] exactly.
                    Output ONLY the JSON array, no prose, no code fence.

                    %s
                    """.formatted(turns.size(), body.toString());

            // Use the configured fact-extractor model (cheap Haiku by default),
            // NOT the main-conversation model (Opus). This was a long-standing
            // bug: the previous Spring AI ChatClient call ignored the
            // factExtractorModel property and ran on whatever model the
            // primary ChatClient was wired to.
            String factModel = props.agent().memory().factExtractorModel();
            ConfiguredProvider provider = chatClients.isEmpty() ? null : chatClients.get(0);
            if (provider == null) {
                log.warn("[memory-extract] no provider configured, skip fact extraction");
                return;
            }

            MessageCreateParams params = MessageCreateParams.builder()
                    .model(factModel)
                    .maxTokens(1024L)
                    .addUserMessage(prompt)
                    .build();

            String reply;
            try {
                Message msg = provider.client().messages().create(params);
                StringBuilder sb = new StringBuilder();
                for (ContentBlock cb : msg.content()) {
                    cb.text().ifPresent(t -> sb.append(t.text()));
                }
                reply = sb.toString();
            } catch (Exception e) {
                log.warn("[memory-extract] fact extraction LLM call failed for user {}: {}",
                        userId, e.getMessage());
                return;
            }

            if (reply.isBlank()) return;
            String arr = sliceFirstArray(reply);
            if (arr == null) {
                log.debug("[memory-extract] no JSON array in extractor reply for user {}", userId);
                return;
            }
            JsonNode node = mapper.readTree(arr);
            if (!node.isArray() || node.isEmpty()) return;

            int saved = 0;
            for (JsonNode item : node) {
                String kind = item.path("kind").asText("fact").trim();
                String content = item.path("content").asText("").trim();
                if (content.isBlank()) continue;
                if (!kind.equals("fact") && !kind.equals("preference") && !kind.equals("rule")) {
                    kind = "fact";
                }
                try {
                    float[] embedding = embeddingService.embed(content);
                    chatFeign.saveFact(new SaveFactRequest(userId, kind, content, null, embedding));
                    saved++;
                } catch (Exception e) {
                    log.warn("[memory-extract] save fact failed for user {}: {}", userId, e.getMessage());
                }
            }
            if (saved > 0) {
                log.info("[memory-extract] saved {} fact(s) for user {} session {} (batch of {} turns, model={})",
                        saved, userId, sessionId, turns.size(), factModel);
            }
        } catch (Exception ex) {
            log.warn("[memory-extract] batch extraction failed for user {}: {}", userId, ex.getMessage());
        }
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
