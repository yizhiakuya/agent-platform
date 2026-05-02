package com.agentplatform.api.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Returned by /internal/memory/facts/query and /internal/memory/facts (after save).
 * The embedding itself is intentionally not exposed — only the human-readable fact.
 *
 * <p>{@code isCurated} marks the fact as a member of the high-confidence "curated"
 * tier — RAG renders these in a separate, higher-priority section. {@code accessCount}
 * is the recall hit counter (used by the promotion job) and {@code curatedAt} is
 * non-null once a fact has been promoted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemoryFactDto(
        UUID id,
        UUID userId,
        String kind,
        String content,
        UUID sourceMessageId,
        OffsetDateTime createdAt,
        boolean isCurated,
        int accessCount,
        OffsetDateTime curatedAt
) {}
