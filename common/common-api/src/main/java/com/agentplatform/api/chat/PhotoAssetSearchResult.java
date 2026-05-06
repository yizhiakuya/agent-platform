package com.agentplatform.api.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PhotoAssetSearchResult(
        UUID id,
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
        double distance,
        double score
) {}
