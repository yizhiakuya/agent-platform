package com.agentplatform.api.auth;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DeviceSeenRequest(
        UUID deviceId,
        UUID userId,
        OffsetDateTime seenAt
) {}
