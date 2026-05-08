package com.agentplatform.api.chat;

import java.util.UUID;

/**
 * Internal request from agent-service after it embeds a photo thumbnail.
 */
public record PhotoAssetEmbeddingRequest(
        UUID assetId,
        float[] embedding,
        String embeddingModel,
        int embeddingDim
) {}
