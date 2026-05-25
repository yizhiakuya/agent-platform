package com.agentplatform.api.chat;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Internal request from agent-service after it embeds a photo thumbnail.
 */
public record PhotoAssetEmbeddingRequest(
        UUID assetId,
        float[] embedding,
        String embeddingModel,
        int embeddingDim
) {
    public PhotoAssetEmbeddingRequest {
        embedding = embedding == null ? null : embedding.clone();
    }

    @Override
    public float[] embedding() {
        return embedding == null ? null : embedding.clone();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof PhotoAssetEmbeddingRequest other
                && Objects.equals(assetId, other.assetId)
                && Arrays.equals(embedding, other.embedding)
                && Objects.equals(embeddingModel, other.embeddingModel)
                && embeddingDim == other.embeddingDim;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(assetId, embeddingModel, embeddingDim);
        result = 31 * result + Arrays.hashCode(embedding);
        return result;
    }

    @Override
    public String toString() {
        return "PhotoAssetEmbeddingRequest[assetId=" + assetId
                + ", embedding=" + Arrays.toString(embedding)
                + ", embeddingModel=" + embeddingModel
                + ", embeddingDim=" + embeddingDim + "]";
    }
}
