package com.agentplatform.api.chat;

import java.util.Arrays;
import java.util.Objects;
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
    public PhotoAssetSearchRequest {
        queryEmbedding = queryEmbedding == null ? null : queryEmbedding.clone();
    }

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

    @Override
    public float[] queryEmbedding() {
        return queryEmbedding == null ? null : queryEmbedding.clone();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof PhotoAssetSearchRequest other
                && Objects.equals(userId, other.userId)
                && Arrays.equals(queryEmbedding, other.queryEmbedding)
                && topK == other.topK
                && Objects.equals(bucketId, other.bucketId)
                && Objects.equals(nameContains, other.nameContains)
                && Objects.equals(dateAfter, other.dateAfter)
                && Objects.equals(dateBefore, other.dateBefore)
                && Objects.equals(minScore, other.minScore)
                && Objects.equals(resultLimit, other.resultLimit)
                && Objects.equals(rankingMode, other.rankingMode)
                && Objects.equals(sortBy, other.sortBy)
                && Objects.equals(sortDirection, other.sortDirection);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(userId, topK, bucketId, nameContains, dateAfter, dateBefore,
                minScore, resultLimit, rankingMode, sortBy, sortDirection);
        result = 31 * result + Arrays.hashCode(queryEmbedding);
        return result;
    }

    @Override
    public String toString() {
        return "PhotoAssetSearchRequest[userId=" + userId
                + ", queryEmbedding=" + Arrays.toString(queryEmbedding)
                + ", topK=" + topK
                + ", bucketId=" + bucketId
                + ", nameContains=" + nameContains
                + ", dateAfter=" + dateAfter
                + ", dateBefore=" + dateBefore
                + ", minScore=" + minScore
                + ", resultLimit=" + resultLimit
                + ", rankingMode=" + rankingMode
                + ", sortBy=" + sortBy
                + ", sortDirection=" + sortDirection + "]";
    }
}
