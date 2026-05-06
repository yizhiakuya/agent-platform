package com.agentplatform.api.chat;

import java.util.List;

/**
 * Public device upload body for {@code POST /api/photos/index/batch}.
 */
public record PhotoAssetBatchRequest(
        List<PhotoAssetUpsertRequest> assets
) {}
