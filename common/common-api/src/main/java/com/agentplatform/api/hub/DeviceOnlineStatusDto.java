package com.agentplatform.api.hub;

import com.agentplatform.protocol.ToolSpec;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record DeviceOnlineStatusDto(
        UUID deviceId,
        boolean online,
        OffsetDateTime connectedAt,
        int toolCount,
        List<ToolSpec> tools
) {}
