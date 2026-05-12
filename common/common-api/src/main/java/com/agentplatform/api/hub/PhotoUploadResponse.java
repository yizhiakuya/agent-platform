package com.agentplatform.api.hub;

import java.util.UUID;

public record PhotoUploadResponse(
        UUID assetId,
        String imageUrl,
        long bytes,
        String contentType
) {}
