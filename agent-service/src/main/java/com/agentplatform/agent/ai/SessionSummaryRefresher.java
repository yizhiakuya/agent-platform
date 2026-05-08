package com.agentplatform.agent.ai;

import com.agentplatform.agent.chat.ContextBudget;
import com.agentplatform.agent.chat.HistoryReplayer;
import com.agentplatform.agent.client.InternalChatFeignClient;
import com.agentplatform.agent.config.AgentProperties;
import com.agentplatform.api.chat.MessageDto;
import com.agentplatform.api.chat.MessageRole;
import com.agentplatform.api.chat.SessionContextSummaryDto;
import com.agentplatform.api.chat.UpsertSessionContextSummaryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Best-effort rolling summarizer for long sessions.
 *
 * <p>Runs after a complete assistant reply. The live chat path keeps the recent
 * tail verbatim; this service compacts older USER/ASSISTANT rows into
 * chat.session_context_summaries so future turns do not replay the whole
 * conversation forever.
 */
@Service
public class SessionSummaryRefresher {

    private static final Logger log = LoggerFactory.getLogger(SessionSummaryRefresher.class);

    private final List<ConfiguredProvider> chatClients;
    private final InternalChatFeignClient chatFeign;
    private final HistoryReplayer historyReplayer;
    private final ExecutorService chatExecutor;
    private final AgentProperties props;
    private final BackgroundLlmClient backgroundLlmClient;

    public SessionSummaryRefresher(List<ConfiguredProvider> chatClients,
                                   InternalChatFeignClient chatFeign,
                                   HistoryReplayer historyReplayer,
                                   ExecutorService chatExecutor,
                                   AgentProperties props,
                                   BackgroundLlmClient backgroundLlmClient) {
        this.chatClients = chatClients == null ? List.of() : chatClients;
        this.chatFeign = chatFeign;
        this.historyReplayer = historyReplayer;
        this.chatExecutor = chatExecutor;
        this.props = props;
        this.backgroundLlmClient = backgroundLlmClient;
    }

    public void refreshAsync(UUID userId, UUID sessionId) {
        AgentProperties.Memory mem = props.agent().memory();
        if (!Boolean.TRUE.equals(mem.enableSessionSummary())) return;
        if (chatClients.isEmpty() || userId == null || sessionId == null) return;
        chatExecutor.execute(() -> refresh(userId, sessionId));
    }

    private void refresh(UUID userId, UUID sessionId) {
        AgentProperties.Memory mem = props.agent().memory();
        try {
            List<MessageDto> rows = historyReplayer.loadRows(sessionId, userId, null);
            rows = rows.stream()
                    .filter(row -> row.role() == MessageRole.USER || row.role() == MessageRole.ASSISTANT)
                    .toList();
            int trigger = Math.max(mem.summaryTriggerMessages(), mem.recentHistoryMessages() + 2);
            if (rows.size() <= trigger) return;

            List<MessageDto> recent = ContextBudget.selectRecent(rows, mem);
            int summaryCount = rows.size() - recent.size();
            if (summaryCount <= 0) return;
            List<MessageDto> older = rows.subList(0, summaryCount);
            MessageDto coveredUntil = older.get(older.size() - 1);

            SessionContextSummaryDto existing = loadExisting(sessionId, userId);
            if (existing != null
                    && existing.coveredUntilMessageId() != null
                    && existing.coveredUntilMessageId().equals(coveredUntil.id())
                    && existing.coveredMessageCount() >= older.size()) {
                return;
            }
            if (existing != null && existing.coveredMessageCount() >= older.size()) {
                return;
            }

            List<MessageDto> delta = older;
            if (existing != null && existing.coveredMessageCount() > 0
                    && existing.coveredMessageCount() < older.size()) {
                delta = older.subList(existing.coveredMessageCount(), older.size());
            }
            if (delta.isEmpty()) return;

            BackgroundLlmClient.CompletionPlan plan = backgroundLlmClient.choosePlan(chatClients, mem.factExtractorModel());
            if (plan == null) {
                log.debug("[session-summary] no background LLM provider configured, skip");
                return;
            }

            String prompt = buildPrompt(existing, delta, older.size(), recent.size(), mem.summaryMaxTokens());
            String summary;
            try {
                summary = backgroundLlmClient.complete(plan, prompt, (long) mem.summaryMaxTokens()).trim();
            } catch (Exception e) {
                log.warn("[session-summary] LLM call failed for user {} session {} provider={} model={}: {}",
                        userId, sessionId, plan.provider().name(), plan.model(), e.getMessage());
                return;
            }
            if (summary.isBlank()) return;

            int estimate = ContextBudget.estimateTextTokens(summary);
            chatFeign.upsertContextSummary(new UpsertSessionContextSummaryRequest(
                    sessionId,
                    userId,
                    coveredUntil.id(),
                    older.size(),
                    summary,
                    estimate));
            log.info("[session-summary] upserted user={} session={} covered={} recentTail={} tokens~{}",
                    userId, sessionId, older.size(), recent.size(), estimate);
        } catch (Exception e) {
            log.warn("[session-summary] refresh failed for user {} session {}: {}",
                    userId, sessionId, e.getMessage());
        }
    }

    private SessionContextSummaryDto loadExisting(UUID sessionId, UUID userId) {
        try {
            return chatFeign.getContextSummary(sessionId, userId);
        } catch (Exception e) {
            return null;
        }
    }

    private static String buildPrompt(SessionContextSummaryDto existing,
                                      List<MessageDto> delta,
                                      int coveredAfterThisRun,
                                      int recentTailCount,
                                      int maxTokens) {
        StringBuilder transcript = new StringBuilder();
        for (int i = 0; i < delta.size(); i++) {
            MessageDto row = delta.get(i);
            transcript.append(i + 1)
                    .append(". ")
                    .append(roleName(row.role()))
                    .append(": ")
                    .append(oneLine(row.content()))
                    .append("\n");
        }

        String existingSummary = existing == null || existing.summary() == null
                ? ""
                : existing.summary().trim();
        return """
                You maintain a compact rolling summary for a chat session.
                Rewrite the summary so it covers the transcript below. Keep durable decisions,
                user goals, constraints, unresolved tasks, references to important artifacts,
                and corrections. Drop filler, duplicate phrasing, and low-value chatter.
                Do not invent facts. Do not include XML/JSON/code fences.
                Target at most %d tokens.

                Existing summary, if any:
                %s

                New transcript segment to merge (%d messages; summary will cover %d older messages total; the newest %d messages remain verbatim outside the summary):
                %s
                """.formatted(
                Math.max(200, maxTokens),
                existingSummary.isBlank() ? "(none)" : existingSummary,
                delta.size(),
                coveredAfterThisRun,
                recentTailCount,
                transcript);
    }

    private static String roleName(MessageRole role) {
        return role == MessageRole.ASSISTANT ? "ASSISTANT" : "USER";
    }

    private static String oneLine(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
