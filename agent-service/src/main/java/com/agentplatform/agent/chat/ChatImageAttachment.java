package com.agentplatform.agent.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Lightweight reference to an image already uploaded to device-hub.
 */
public record ChatImageAttachment(
        @NotBlank @Pattern(regexp = "/api/uploads/photos/[0-9a-fA-F-]{36}") String imageUrl,
        @Size(max = 80) String assetId,
        @Pattern(regexp = "image/(jpeg|png|webp)") String contentType,
        @PositiveOrZero Long bytes,
        @Size(max = 160) String name,
        @PositiveOrZero Integer width,
        @PositiveOrZero Integer height,
        @Size(max = 40) String source,
        @Size(max = 120) String mediaRef,
        @Size(max = 20) String mediaType,
        @Size(max = 80) String mediaId,
        @Size(max = 80) String sourceTool,
        @Size(max = 160) String bucketName,
        @PositiveOrZero Long dateTakenMs
) {}
