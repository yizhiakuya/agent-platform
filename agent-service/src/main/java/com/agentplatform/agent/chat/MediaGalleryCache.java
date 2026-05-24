package com.agentplatform.agent.chat;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface MediaGalleryCache {

    Key key(UUID userId, UUID deviceId, String kind, String material);

    Optional<JsonNode> getJson(Key key);

    void putJson(Key key, JsonNode value, Duration ttl);

    Optional<Thumbnail> getThumbnail(Key key);

    void putThumbnail(Key key, byte[] bytes, String contentType, Duration ttl);

    void invalidateDevice(UUID userId, UUID deviceId);

    record Key(UUID userId, UUID deviceId, String kind, String material) {}

    record Thumbnail(byte[] bytes, String contentType) {
        public Thumbnail {
            bytes = bytes == null ? new byte[0] : bytes.clone();
            contentType = contentType == null || contentType.isBlank() ? "image/jpeg" : contentType;
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
