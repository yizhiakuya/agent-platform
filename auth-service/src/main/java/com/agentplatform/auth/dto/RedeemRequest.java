package com.agentplatform.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RedeemRequest(
        @NotBlank @Size(max = 64) String name,
        @Size(max = 128) String model,
        @Size(max = 32) String osVersion
) {}
