package com.agentplatform.api.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Rolling summary of older USER/ASSISTANT turns in one conversation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionContextSummaryDto(
        UUID id,
        UUID sessionId,
        UUID userId,
        UUID coveredUntilMessageId,
        int coveredMessageCount,
        String summary,
        int tokenEstimate,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
