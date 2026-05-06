package com.agentplatform.api.chat;

/**
 * Summary returned after a device uploads a batch of indexed photo metadata.
 */
public record PhotoAssetBatchResponse(
        int received,
        int upserted,
        int pendingEmbedding
) {}
