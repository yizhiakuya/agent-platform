package com.agentplatform.agent.chat;

import com.agentplatform.agent.ai.PersonaLoader;
import com.agentplatform.agent.ai.SkillRegistry;
import com.agentplatform.agent.client.AuthInternalClient;
import com.agentplatform.agent.client.InternalChatFeignClient;
import com.agentplatform.agent.config.AgentProperties;
import com.agentplatform.api.auth.UserPreferenceDto;
import com.agentplatform.api.chat.MemoryFactDto;
import com.agentplatform.api.chat.MessageDto;
import com.agentplatform.api.chat.SessionArtifactDto;
import com.agentplatform.api.chat.SessionContextSummaryDto;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Central context policy for one LLM request.
 *
 * <p>V1 keeps current behavior but moves it behind a single boundary:
 * stable system prompt, recalled memories, recent text history, and a
 * lightweight session working set. Later token-budgeting and summaries should
 * evolve here instead of scattering more policy across ChatService.
 */
@Service
public class ContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(ContextAssembler.class);
    private static final int ARTIFACT_LIMIT = 12;

    private final PersonaLoader personaLoader;
    private final SkillRegistry skillRegistry;
    private final AuthInternalClient authClient;
    private final InternalChatFeignClient chatClientFeign;
    private final AgentProperties props;
    private final HistoryReplayer historyReplayer;

    public ContextAssembler(PersonaLoader personaLoader,
                            SkillRegistry skillRegistry,
                            AuthInternalClient authClient,
                            InternalChatFeignClient chatClientFeign,
                            AgentProperties props,
                            HistoryReplayer historyReplayer) {
        this.personaLoader = personaLoader;
        this.skillRegistry = skillRegistry;
        this.authClient = authClient;
        this.chatClientFeign = chatClientFeign;
        this.props = props;
        this.historyReplayer = historyReplayer;
    }

    public ContextBundle assemble(UUID userId,
                                  UUID sessionId,
                                  String currentMessage,
                                  List<MemoryFactDto> memories) {
        String stableSystemText = PromptAssembler.buildSystemText(
                personaLoader.getBundle(),
                loadUserPrefs(userId),
                skillRegistry.all());

        boolean cacheEnabled = Boolean.TRUE.equals(props.agent().memory().enablePromptCache())
                && stableSystemText.length() >= PromptAssembler.PROMPT_CACHE_MIN_CHARS;
        List<TextBlockParam> systemBlocks = PromptAssembler.buildSystemBlocks(stableSystemText, cacheEnabled);

        AgentProperties.Memory memory = props.agent().memory();
        List<SessionArtifactDto> artifacts = loadArtifacts(sessionId, userId);
        SessionContextSummaryDto summary = loadSummary(sessionId, userId);
        String memoryBlock = PromptAssembler.formatMemoryBlock(memories);
        String artifactBlock = PromptAssembler.formatArtifactBlock(artifacts);
        boolean summaryEnabled = Boolean.TRUE.equals(memory.enableSessionSummary());
        String summaryBlock = summaryEnabled
                ? PromptAssembler.formatSessionSummaryBlock(
                        summary == null ? "" : summary.summary(),
                        summary == null ? 0 : summary.coveredMessageCount())
                : "";
        String userText = PromptAssembler.composeUserText(
                memoryBlock,
                artifactBlock,
                summaryBlock,
                currentMessage);

        List<MessageDto> allRows = historyReplayer.loadRows(sessionId, userId, currentMessage);
        List<MessageDto> historyRows = summaryEnabled ? ContextBudget.selectRecent(allRows, memory) : allRows;
        List<MessageParam> anthropicMessages = historyReplayer.toParams(historyRows);
        ContextStats stats = buildStats(stableSystemText, memoryBlock, artifactBlock, summaryBlock,
                historyRows, allRows.size(), currentMessage, memory);
        log.debug("context user={} session={} totalTokens~{} system~{} memory~{} artifacts~{} summary~{} history~{} "
                        + "historyRows={}/{} summarizedRows={} maxInput={}",
                userId, sessionId, stats.totalTokens(), stats.systemTokens(), stats.memoryTokens(),
                stats.artifactTokens(), stats.summaryTokens(), stats.historyTokens(),
                stats.recentHistoryMessages(), stats.totalHistoryMessages(), stats.summarizedMessages(),
                stats.maxInputTokens());
        return new ContextBundle(
                stableSystemText,
                systemBlocks,
                userText,
                anthropicMessages,
                historyRows,
                artifacts,
                summary,
                stats);
    }

    private String loadUserPrefs(UUID userId) {
        try {
            UserPreferenceDto pref = authClient.getPreferences(userId);
            if (pref == null) return "";
            String content = pref.content();
            return content == null ? "" : content.trim();
        } catch (Exception e) {
            log.debug("loadUserPrefs failed for user {}: {}", userId, e.getMessage());
            return "";
        }
    }

    private List<SessionArtifactDto> loadArtifacts(UUID sessionId, UUID userId) {
        if (sessionId == null) return List.of();
        try {
            List<SessionArtifactDto> rows = chatClientFeign.listArtifacts(sessionId, userId, ARTIFACT_LIMIT);
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            log.debug("loadArtifacts failed for user {} session {}: {}", userId, sessionId, e.getMessage());
            return List.of();
        }
    }

    private SessionContextSummaryDto loadSummary(UUID sessionId, UUID userId) {
        if (sessionId == null) return null;
        if (!Boolean.TRUE.equals(props.agent().memory().enableSessionSummary())) return null;
        try {
            return chatClientFeign.getContextSummary(sessionId, userId);
        } catch (Exception e) {
            log.debug("loadSummary failed for user {} session {}: {}", userId, sessionId, e.getMessage());
            return null;
        }
    }

    private ContextStats buildStats(String stableSystemText,
                                    String memoryBlock,
                                    String artifactBlock,
                                    String summaryBlock,
                                    List<MessageDto> historyRows,
                                    int totalHistoryRows,
                                    String currentMessage,
                                    AgentProperties.Memory memory) {
        int systemTokens = ContextBudget.estimateTextTokens(stableSystemText);
        int memoryTokens = ContextBudget.estimateTextTokens(memoryBlock);
        int artifactTokens = ContextBudget.estimateTextTokens(artifactBlock);
        int summaryTokens = ContextBudget.estimateTextTokens(summaryBlock);
        int historyTokens = ContextBudget.estimateMessagesTokens(historyRows);
        int currentTokens = ContextBudget.estimateTextTokens(currentMessage);
        int total = systemTokens + memoryTokens + artifactTokens + summaryTokens + historyTokens + currentTokens;
        int recentRows = historyRows == null ? 0 : historyRows.size();
        return new ContextStats(
                systemTokens,
                memoryTokens,
                artifactTokens,
                summaryTokens,
                historyTokens,
                currentTokens,
                total,
                totalHistoryRows,
                recentRows,
                Math.max(0, totalHistoryRows - recentRows),
                memory.maxInputTokens());
    }
}
