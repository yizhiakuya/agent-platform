package com.agentplatform.auth.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DeviceDto(
        UUID id,
        String name,
        String model,
        String osVersion,
        OffsetDateTime lastSeenAt,
        OffsetDateTime createdAt
) {}
