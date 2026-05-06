package com.agentplatform.api.chat;

/**
 * One Android MediaStore image row uploaded into the server-side photo index.
 *
 * <p>The original image stays on the phone. The server stores only metadata
 * plus a bounded thumbnail for search result previews and image embeddings.
 */
public record PhotoAssetUpsertRequest(
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
        String thumbB64
) {}
