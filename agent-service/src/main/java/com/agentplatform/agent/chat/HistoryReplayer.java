package com.agentplatform.agent.chat;

import com.agentplatform.agent.client.InternalChatFeignClient;
import com.agentplatform.api.chat.MessageDto;
import com.agentplatform.api.chat.MessageRole;
import com.anthropic.models.messages.MessageParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Loads chat history from chat-service and converts it to the SDK's
 * {@code MessageParam} shape for replay into the next LLM turn.
 *
 * <p>Tool-call audit rows ({@code TOOL_CALL} / {@code TOOL_RESULT}) are
 * filtered out — only USER/ASSISTANT text turns are replayed. The current
 * user message (already persisted by ChatService before this is called) is
 * stripped from the tail by exact-content match, so the LLM doesn't see it
 * twice (we add it back ourselves immediately after).
 *
 * <p>Failures degrade to an empty list — the LLM just loses prior context
 * for this turn rather than blocking the stream.
 */
@Service
public class HistoryReplayer {

    private static final Logger log = LoggerFactory.getLogger(HistoryReplayer.class);

    private final InternalChatFeignClient chatClientFeign;

    public HistoryReplayer(InternalChatFeignClient chatClientFeign) {
        this.chatClientFeign = chatClientFeign;
    }

    public List<MessageParam> loadAsParams(UUID sessionId, UUID userId, String currentMessage) {
        if (sessionId == null) return new ArrayList<>();
        try {
            List<MessageDto> rows = chatClientFeign.listMessages(sessionId, userId);
            if (rows == null || rows.isEmpty()) return new ArrayList<>();
            int n = rows.size();
            if (rows.get(n - 1).role() == MessageRole.USER
                    && currentMessage.equals(rows.get(n - 1).content())) {
                rows = rows.subList(0, n - 1);
            }
            List<MessageParam> out = new ArrayList<>(rows.size());
            for (MessageDto m : rows) {
                String content = m.content() == null ? "" : m.content();
                switch (m.role()) {
                    case USER -> {
                        if (!content.isBlank()) {
                            out.add(MessageParam.builder()
                                    .role(MessageParam.Role.USER)
                                    .content(content)
                                    .build());
                        }
                    }
                    case ASSISTANT -> {
                        if (!content.isBlank()) {
                            out.add(MessageParam.builder()
                                    .role(MessageParam.Role.ASSISTANT)
                                    .content(content)
                                    .build());
                        }
                    }
                    case TOOL_CALL, TOOL_RESULT -> { /* skip — audit only */ }
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Failed to load history for session {}: {}", sessionId, e.getMessage());
            return new ArrayList<>();
        }
    }
}
