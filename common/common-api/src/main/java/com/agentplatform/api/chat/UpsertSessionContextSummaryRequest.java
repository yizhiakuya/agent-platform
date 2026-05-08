package com.agentplatform.api.chat;

import java.util.UUID;

/**
 * Internal request for replacing a session's rolling context summary.
 */
public record UpsertSessionContextSummaryRequest(
        UUID sessionId,
        UUID userId,
        UUID coveredUntilMessageId,
        int coveredMessageCount,
        String summary,
        int tokenEstimate
) {}
