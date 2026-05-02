package com.agentplatform.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyRequest(@NotBlank String token) {}
