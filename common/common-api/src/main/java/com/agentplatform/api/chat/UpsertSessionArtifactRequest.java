package com.agentplatform.api.chat;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Internal request for adding one session-scoped working-set artifact.
 */
public record UpsertSessionArtifactRequest(
        UUID sessionId,
        UUID userId,
        UUID messageId,
        String artifactType,
        String artifactKey,
        String title,
        String summary,
        JsonNode metadata
) {}
