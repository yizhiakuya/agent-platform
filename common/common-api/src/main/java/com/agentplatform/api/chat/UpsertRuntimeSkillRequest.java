package com.agentplatform.api.chat;

import java.util.UUID;

public record UpsertRuntimeSkillRequest(
        UUID userId,
        String name,
        String description,
        String body,
        Boolean enabled
) {}
