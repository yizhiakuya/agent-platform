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
 *                         model and chat.memory_embeddings schema (currently 1024 dims).
 * @param curated          Optional. When true, save directly into the high-confidence tier.
 */
public record SaveFactRequest(
        UUID userId,
        String kind,
        String content,
        UUID sourceMessageId,
        float[] embedding,
        Boolean curated
) {
    public SaveFactRequest(UUID userId,
                           String kind,
                           String content,
                           UUID sourceMessageId,
                           float[] embedding) {
        this(userId, kind, content, sourceMessageId, embedding, null);
    }
}
