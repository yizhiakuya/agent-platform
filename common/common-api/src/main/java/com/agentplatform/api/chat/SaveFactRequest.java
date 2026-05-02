package com.agentplatform.api.chat;

import java.util.UUID;

/**
 * Persist one extracted long-term memory fact for a user.
 *
 * @param userId           Owner. Required.
 * @param kind             {@code fact} | {@code preference} | {@code rule}. Required.
 * @param content          Plain-text fact body. Required.
 * @param sourceMessageId  Optional pointer back to the message this fact was extracted from.
 * @param embedding        Vector representation. Length must match the configured embedding
 *                         model (currently 1536 dims for text-embedding-3-small).
 */
public record SaveFactRequest(
        UUID userId,
        String kind,
        String content,
        UUID sourceMessageId,
        float[] embedding
) {}
