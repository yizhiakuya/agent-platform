package com.agentplatform.agent.chat;

/**
 * Estimated context shape for one request. These numbers are approximate and
 * exist for policy/logging, not billing.
 */
public record ContextStats(
        int systemTokens,
        int memoryTokens,
        int artifactTokens,
        int summaryTokens,
        int historyTokens,
        int currentMessageTokens,
        int totalTokens,
        int totalHistoryMessages,
        int recentHistoryMessages,
        int summarizedMessages,
        int maxInputTokens
) {}
