package com.agentplatform.auth.dto;

import java.util.UUID;

public record RedeemResponse(UUID deviceId, String token) {}
