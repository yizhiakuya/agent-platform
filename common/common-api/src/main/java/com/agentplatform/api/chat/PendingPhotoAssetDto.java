package com.agentplatform.api.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PendingPhotoAssetDto(
        UUID id,
        UUID userId,
        UUID deviceId,
        String mediaStoreId,
        String name,
        String mimeType,
        String thumbB64
) {}
