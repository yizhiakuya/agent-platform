package com.agentplatform.agent.ai;

import com.agentplatform.agent.client.InternalChatFeignClient;
import com.agentplatform.agent.config.AgentProperties;
import com.agentplatform.api.chat.SaveFactRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Background fact extractor: after every assistant turn, asks the LLM to pull
 * any durable user-specific facts/preferences/rules out of the (user, assistant)
 * exchange, embeds each fact, and persists it to chat-service's memory store.
 *
 * <p>Runs entirely off the SSE response path on the {@code chatExecutor} thread
 * pool — the user never waits for it, and any failure is logged at WARN and
 * dropped (memory is a best-effort enrichment, not a contract).
 */
@Service
public class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);

    private final List<ChatClient> chatClients;
    private final EmbeddingService embeddingService;
    private final InternalChatFeignClient chatFeign;
    private final ExecutorService chatExecutor;
    private final ObjectMapper mapper;
    private final AgentProperties props;

    public MemoryExtractor(List<ChatClient> chatClients,
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
     * Schedule async fact extraction. Returns immediately; failure is silent.
     */
    public void extractAsync(UUID userId, UUID sessionId, String userMsg, String assistantMsg) {
        if (chatClients == null || chatClients.isEmpty()) return;
        if (userId == null) return;
        if ((userMsg == null || userMsg.isBlank()) && (assistantMsg == null || assistantMsg.isBlank())) return;
        chatExecutor.execute(() -> runExtraction(userId, sessionId, safe(userMsg), safe(assistantMsg)));
    }

    private void runExtraction(UUID userId, UUID sessionId, String userMsg, String assistantMsg) {
        try {
            String prompt = """
                    You analyse one (user, assistant) exchange and extract durable user-specific
                    facts, preferences, or rules worth remembering for future conversations.
                    Skip transient details (one-off questions, generic advice). Output a JSON
                    array, e.g.
                    [
                      {"kind":"preference","content":"用户喜欢简短中文回答"},
                      {"kind":"fact","content":"用户的小狗叫旺财"}
                    ]
                    Allowed kind values: fact | preference | rule.
                    If nothing worth remembering, output [] exactly.
                    Output ONLY the JSON array, no prose, no code fence.

                    USER: %s
                    ASSISTANT: %s
                    """.formatted(userMsg, assistantMsg);

            String reply = chatClients.get(0).prompt().user(prompt).call().content();
            if (reply == null) return;
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
                log.info("[memory-extract] saved {} fact(s) for user {} session {}", saved, userId, sessionId);
            }
        } catch (Exception ex) {
            log.warn("[memory-extract] extraction failed for user {}: {}", userId, ex.getMessage());
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
