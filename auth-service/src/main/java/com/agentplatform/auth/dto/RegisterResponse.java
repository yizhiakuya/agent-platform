package com.agentplatform.auth.dto;

import java.util.UUID;

public record RegisterResponse(UUID userId, String username) {}
