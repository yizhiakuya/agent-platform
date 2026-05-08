package com.agentplatform.api.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuntimeSkillDto(
        UUID id,
        UUID userId,
        String name,
        String description,
        String body,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
