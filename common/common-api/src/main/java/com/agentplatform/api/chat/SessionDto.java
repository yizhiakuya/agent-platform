package com.agentplatform.api.chat;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SessionDto(
        UUID id,
        UUID userId,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime lastMessageAt
) {}
