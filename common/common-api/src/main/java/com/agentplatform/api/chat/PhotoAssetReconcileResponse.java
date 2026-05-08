package com.agentplatform.api.chat;

/**
 * Summary returned after deleted device photos are tombstoned server-side.
 */
public record PhotoAssetReconcileResponse(
        int current,
        int deleted
) {}
