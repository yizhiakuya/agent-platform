package com.agentplatform.api.hub;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DeviceOnlineStatusDto(
        UUID deviceId,
        boolean online,
        OffsetDateTime connectedAt,
        int toolCount
) {}
