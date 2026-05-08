package com.agentplatform.api.chat;

import java.util.UUID;

/**
 * Internal vector search request for indexed photos.
 */
public record PhotoAssetSearchRequest(
        UUID userId,
        float[] queryEmbedding,
        int topK,
        String bucketId,
        String nameContains,
        Long dateAfter,
        Long dateBefore,
        Double minScore,
        Integer resultLimit,
        String rankingMode,
        String sortBy,
        String sortDirection
) {
    public PhotoAssetSearchRequest(UUID userId,
                                   float[] queryEmbedding,
                                   int topK,
                                   String bucketId,
                                   Long dateAfter,
                                   Long dateBefore,
                                   Double minScore) {
        this(userId, queryEmbedding, topK, bucketId, null, dateAfter, dateBefore,
                minScore, null, null, null, null);
    }

    public PhotoAssetSearchRequest(UUID userId,
                                   float[] queryEmbedding,
                                   int topK,
                                   String bucketId,
                                   Long dateAfter,
                                   Long dateBefore,
                                   Double minScore,
                                   Integer resultLimit,
                                   String rankingMode,
                                   String sortBy,
                                   String sortDirection) {
        this(userId, queryEmbedding, topK, bucketId, null, dateAfter, dateBefore,
                minScore, resultLimit, rankingMode, sortBy, sortDirection);
    }
}
