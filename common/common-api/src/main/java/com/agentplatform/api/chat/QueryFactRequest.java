package com.agentplatform.api.chat;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Vector similarity query for a user's long-term memory facts.
 *
 * @param userId          Owner whose facts are searched. Required.
 * @param queryEmbedding  Embedding of the query text. Same dim as stored facts.
 * @param topK            Max number of facts to return; the service may clamp this.
 */
public record QueryFactRequest(
        UUID userId,
        float[] queryEmbedding,
        int topK
) {
    public QueryFactRequest {
        queryEmbedding = queryEmbedding == null ? null : queryEmbedding.clone();
    }

    @Override
    public float[] queryEmbedding() {
        return queryEmbedding == null ? null : queryEmbedding.clone();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof QueryFactRequest other
                && Objects.equals(userId, other.userId)
                && Arrays.equals(queryEmbedding, other.queryEmbedding)
                && topK == other.topK;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(userId, topK);
        result = 31 * result + Arrays.hashCode(queryEmbedding);
        return result;
    }

    @Override
    public String toString() {
        return "QueryFactRequest[userId=" + userId
                + ", queryEmbedding=" + Arrays.toString(queryEmbedding)
                + ", topK=" + topK + "]";
    }
}
