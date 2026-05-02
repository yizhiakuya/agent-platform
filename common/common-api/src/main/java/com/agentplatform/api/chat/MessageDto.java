package com.agentplatform.api.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageDto(
        UUID id,
        UUID sessionId,
        MessageRole role,
        String content,
        JsonNode metadata,
        OffsetDateTime createdAt
) {}
