package com.agentplatform.agent.chat;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public class NoopMediaGalleryCache implements MediaGalleryCache {

    @Override
    public Key key(UUID userId, UUID deviceId, String kind, String material) {
        return new Key(userId, deviceId, kind, material);
    }

    @Override
    public Optional<JsonNode> getJson(Key key) {
        return Optional.empty();
    }

    @Override
    public void putJson(Key key, JsonNode value, Duration ttl) {
        // Intentionally disabled when no cache backend is configured.
    }

    @Override
    public Optional<Thumbnail> getThumbnail(Key key) {
        return Optional.empty();
    }

    @Override
    public void putThumbnail(Key key, byte[] bytes, String contentType, Duration ttl) {
        // Intentionally disabled when no cache backend is configured.
    }

    @Override
    public void invalidateDevice(UUID userId, UUID deviceId) {
        // Intentionally disabled when no cache backend is configured.
    }
}
