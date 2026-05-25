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
        return assemble(userId, sessionId, currentMessage, memories, loadUserContextSettings(userId));
    }

    public ContextBundle assemble(UUID userId,
                                  UUID sessionId,
                                  String currentMessage,
                                  List<MemoryFactDto> memories,
                                  UserContextSettings userContextSettings) {
        UserContextSettings settings = userContextSettings == null
                ? UserContextSettings.defaults()
                : userContextSettings;
        String stableSystemText = PromptAssembler.buildSystemText(
                personaLoader.getBundle(),
                settings.promptPreferences(),
                skillRegistry.all(userId));

        boolean cacheEnabled = Boolean.TRUE.equals(props.agent().memory().enablePromptCache())
                && stableSystemText.length() >= PromptAssembler.PROMPT_CACHE_MIN_CHARS;
        List<TextBlockParam> systemBlocks = PromptAssembler.buildSystemBlocks(stableSystemText, cacheEnabled);

        AgentProperties.Memory memory = props.agent().memory();
        List<SessionArtifactDto> artifacts = loadArtifacts(sessionId, userId);
        SessionContextSummaryDto summary = loadSummary(sessionId, userId);
        boolean hasSummary = hasUsableSummary(summary);
        String memoryBlock = PromptAssembler.formatMemoryBlock(memories);
        String artifactBlock = PromptAssembler.formatArtifactBlock(artifacts);
        boolean summaryEnabled = Boolean.TRUE.equals(memory.enableSessionSummary());
        String summaryBlock = summaryEnabled && hasSummary
                ? PromptAssembler.formatSessionSummaryBlock(
                        summary.summary(),
                        summary.coveredMessageCount())
                : "";
        String userText = PromptAssembler.composeUserText(
                memoryBlock,
                artifactBlock,
                summaryBlock,
                currentMessage);

        List<MessageDto> allRows = historyReplayer.loadRows(sessionId, userId, currentMessage);
        List<MessageDto> historyRows = summaryEnabled && hasSummary
                ? ContextBudget.selectRecent(allRows, memory)
                : allRows;
        List<MessageParam> anthropicMessages = historyReplayer.toParams(historyRows);
        ContextStats stats = buildStats(new ContextStatsInput()
                .withStableSystemText(stableSystemText)
                .withMemoryBlock(memoryBlock)
                .withArtifactBlock(artifactBlock)
                .withSummaryBlock(summaryBlock)
                .withHistoryRows(historyRows)
                .withTotalHistoryRows(allRows.size())
                .withCurrentMessage(currentMessage)
                .withMemory(memory));
        log.debug("context user={} session={} totalTokens~{} system~{} memory~{} artifacts~{} summary~{} history~{} "
                        + "historyRows={}/{} summarizedRows={} summaryPresent={} maxInput={}",
                userId, sessionId, stats.totalTokens(), stats.systemTokens(), stats.memoryTokens(),
                stats.artifactTokens(), stats.summaryTokens(), stats.historyTokens(),
                stats.recentHistoryMessages(), stats.totalHistoryMessages(), stats.summarizedMessages(),
                hasSummary, stats.maxInputTokens());
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

    public UserContextSettings loadUserContextSettings(UUID userId) {
        try {
            UserPreferenceDto pref = authClient.getPreferences(userId);
            if (pref == null) return UserContextSettings.defaults();
            String content = pref.content();
            return new UserContextSettings(
                    content == null ? "" : content.trim(),
                    pref.autoMemoryEnabledOrDefault());
        } catch (Exception e) {
            log.debug("loadUserContextSettings failed for user {}: {}", userId, e.getMessage());
            return UserContextSettings.defaults();
        }
    }

    public record UserContextSettings(String promptPreferences, boolean autoMemoryEnabled) {
        public static UserContextSettings defaults() {
            return new UserContextSettings("", true);
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

    private static boolean hasUsableSummary(SessionContextSummaryDto summary) {
        return summary != null
                && summary.coveredMessageCount() > 0
                && summary.summary() != null
                && !summary.summary().isBlank();
    }

    private ContextStats buildStats(ContextStatsInput input) {
        int systemTokens = ContextBudget.estimateTextTokens(input.stableSystemText());
        int memoryTokens = ContextBudget.estimateTextTokens(input.memoryBlock());
        int artifactTokens = ContextBudget.estimateTextTokens(input.artifactBlock());
        int summaryTokens = ContextBudget.estimateTextTokens(input.summaryBlock());
        int historyTokens = ContextBudget.estimateMessagesTokens(input.historyRows());
        int currentTokens = ContextBudget.estimateTextTokens(PromptAssembler.formatCurrentTimeBlock())
                + ContextBudget.estimateTextTokens(input.currentMessage());
        int total = systemTokens + memoryTokens + artifactTokens + summaryTokens + historyTokens + currentTokens;
        int recentRows = input.historyRows() == null ? 0 : input.historyRows().size();
        return new ContextStats(
                systemTokens,
                memoryTokens,
                artifactTokens,
                summaryTokens,
                historyTokens,
                currentTokens,
                total,
                input.totalHistoryRows(),
                recentRows,
                Math.max(0, input.totalHistoryRows() - recentRows),
                input.memory().maxInputTokens());
    }

    private static final class ContextStatsInput {
        private String stableSystemText;
        private String memoryBlock;
        private String artifactBlock;
        private String summaryBlock;
        private List<MessageDto> historyRows;
        private int totalHistoryRows;
        private String currentMessage;
        private AgentProperties.Memory memory;

        private ContextStatsInput withStableSystemText(String stableSystemText) {
            this.stableSystemText = stableSystemText;
            return this;
        }

        private ContextStatsInput withMemoryBlock(String memoryBlock) {
            this.memoryBlock = memoryBlock;
            return this;
        }

        private ContextStatsInput withArtifactBlock(String artifactBlock) {
            this.artifactBlock = artifactBlock;
            return this;
        }

        private ContextStatsInput withSummaryBlock(String summaryBlock) {
            this.summaryBlock = summaryBlock;
            return this;
        }

        private ContextStatsInput withHistoryRows(List<MessageDto> historyRows) {
            this.historyRows = historyRows;
            return this;
        }

        private ContextStatsInput withTotalHistoryRows(int totalHistoryRows) {
            this.totalHistoryRows = totalHistoryRows;
            return this;
        }

        private ContextStatsInput withCurrentMessage(String currentMessage) {
            this.currentMessage = currentMessage;
            return this;
        }

        private ContextStatsInput withMemory(AgentProperties.Memory memory) {
            this.memory = memory;
            return this;
        }

        private String stableSystemText() {
            return stableSystemText;
        }

        private String memoryBlock() {
            return memoryBlock;
        }

        private String artifactBlock() {
            return artifactBlock;
        }

        private String summaryBlock() {
            return summaryBlock;
        }

        private List<MessageDto> historyRows() {
            return historyRows;
        }

        private int totalHistoryRows() {
            return totalHistoryRows;
        }

        private String currentMessage() {
            return currentMessage;
        }

        private AgentProperties.Memory memory() {
            return memory;
        }
    }
}
