package com.agentplatform.api.chat;

import java.util.Arrays;
import java.util.Objects;
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
    public SaveFactRequest {
        embedding = embedding == null ? null : embedding.clone();
    }

    public SaveFactRequest(UUID userId,
                           String kind,
                           String content,
                           UUID sourceMessageId,
                           float[] embedding) {
        this(userId, kind, content, sourceMessageId, embedding, null);
    }

    @Override
    public float[] embedding() {
        return embedding == null ? null : embedding.clone();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof SaveFactRequest other
                && Objects.equals(userId, other.userId)
                && Objects.equals(kind, other.kind)
                && Objects.equals(content, other.content)
                && Objects.equals(sourceMessageId, other.sourceMessageId)
                && Arrays.equals(embedding, other.embedding)
                && Objects.equals(curated, other.curated);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(userId, kind, content, sourceMessageId, curated);
        result = 31 * result + Arrays.hashCode(embedding);
        return result;
    }

    @Override
    public String toString() {
        return "SaveFactRequest[userId=" + userId
                + ", kind=" + kind
                + ", content=" + content
                + ", sourceMessageId=" + sourceMessageId
                + ", embedding=" + Arrays.toString(embedding)
                + ", curated=" + curated + "]";
    }
}
