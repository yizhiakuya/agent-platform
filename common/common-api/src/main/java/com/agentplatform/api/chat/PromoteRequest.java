package com.agentplatform.api.chat;

import java.util.UUID;

/**
 * Body for {@code POST /internal/memory/promote} — promotes raw {@code memory_facts}
 * rows for {@code userId} into the curated tier when their {@code access_count} has
 * crossed {@code minAccessCount}.
 *
 * @param userId          Owner whose facts to consider. Required.
 * @param minAccessCount  Threshold; facts with {@code access_count >= minAccessCount}
 *                        become eligible. Caller-side default of 2 is sensible.
 * @param maxToPromote    Cap on rows promoted in this call (defensive — keeps the
 *                        UPDATE bounded). Caller-side default of 20 is sensible.
 */
public record PromoteRequest(
        UUID userId,
        int minAccessCount,
        int maxToPromote
) {}
