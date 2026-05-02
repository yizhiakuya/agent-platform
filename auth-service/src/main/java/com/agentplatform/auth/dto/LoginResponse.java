package com.agentplatform.auth.dto;

import java.util.UUID;

public record LoginResponse(UUID userId, String username, String token) {}
