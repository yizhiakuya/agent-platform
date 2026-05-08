package com.agentplatform.api.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Lightweight pointer to a tool-produced object that may be referenced later
 * in the same conversation. The artifact stores IDs and summaries, not large
 * binary payloads.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionArtifactDto(
        UUID id,
        UUID sessionId,
        UUID userId,
        UUID messageId,
        String artifactType,
        String artifactKey,
        String title,
        String summary,
        JsonNode metadata,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
