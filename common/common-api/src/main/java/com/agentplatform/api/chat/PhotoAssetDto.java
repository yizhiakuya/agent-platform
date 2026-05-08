package com.agentplatform.api.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PhotoAssetDto(
        UUID id,
        UUID userId,
        UUID deviceId,
        String mediaStoreId,
        String name,
        String bucketId,
        String bucketName,
        Long dateTakenMs,
        Long dateModifiedSec,
        Long sizeBytes,
        Integer width,
        Integer height,
        String mimeType,
        String contentHash,
        String thumbB64,
        String embeddingModel,
        Integer embeddingDim,
        OffsetDateTime indexedAt,
        OffsetDateTime updatedAt
) {}
