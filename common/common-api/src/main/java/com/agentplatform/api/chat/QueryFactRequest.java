package com.agentplatform.api.chat;

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
) {}
