package com.agentplatform.agent.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class RedisMediaGalleryCache implements MediaGalleryCache {

    private static final Logger log = LoggerFactory.getLogger(RedisMediaGalleryCache.class);
    private static final String PREFIX = "agent:media-gallery:v1";
    private static final Duration INDEX_TTL_PADDING = Duration.ofMinutes(5);

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisTemplate<String, byte[]> bytesRedisTemplate;
    private final ObjectMapper mapper;

    public RedisMediaGalleryCache(StringRedisTemplate stringRedisTemplate,
                                  RedisTemplate<String, byte[]> bytesRedisTemplate,
                                  ObjectMapper mapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.bytesRedisTemplate = bytesRedisTemplate;
        this.mapper = mapper;
    }

    @Override
    public Key key(UUID userId, UUID deviceId, String kind, String material) {
        return new Key(userId, deviceId, kind, material);
    }

    @Override
    public Optional<JsonNode> getJson(Key key) {
        try {
            String raw = stringRedisTemplate.opsForValue().get(cacheKey(key));
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(mapper.readTree(raw));
        } catch (Exception e) {
            log.debug("media gallery Redis JSON cache read failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void putJson(Key key, JsonNode value, Duration ttl) {
        if (value == null || ttl == null || ttl.isZero() || ttl.isNegative()) return;
        try {
            String redisKey = cacheKey(key);
            stringRedisTemplate.opsForValue().set(redisKey, mapper.writeValueAsString(value), ttl);
            index(redisKey, key, ttl);
        } catch (Exception e) {
            log.debug("media gallery Redis JSON cache write failed: {}", e.getMessage());
        }
    }

    @Override
    public Optional<Thumbnail> getThumbnail(Key key) {
        try {
            String redisKey = cacheKey(key);
            byte[] bytes = bytesRedisTemplate.opsForValue().get(redisKey);
            if (bytes == null || bytes.length == 0) {
                return Optional.empty();
            }
            String contentType = stringRedisTemplate.opsForValue().get(metadataKey(redisKey));
            return Optional.of(new Thumbnail(bytes, contentType));
        } catch (Exception e) {
            log.debug("media gallery Redis thumbnail cache read failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void putThumbnail(Key key, byte[] bytes, String contentType, Duration ttl) {
        if (bytes == null || bytes.length == 0 || ttl == null || ttl.isZero() || ttl.isNegative()) return;
        try {
            String redisKey = cacheKey(key);
            bytesRedisTemplate.opsForValue().set(redisKey, bytes.clone(), ttl);
            stringRedisTemplate.opsForValue().set(metadataKey(redisKey),
                    contentType == null || contentType.isBlank() ? "image/jpeg" : contentType,
                    ttl);
            index(redisKey, key, ttl);
        } catch (Exception e) {
            log.debug("media gallery Redis thumbnail cache write failed: {}", e.getMessage());
        }
    }

    @Override
    public void invalidateDevice(UUID userId, UUID deviceId) {
        if (userId == null || deviceId == null) return;
        String indexKey = deviceIndexKey(userId, deviceId);
        try {
            Set<String> keys = stringRedisTemplate.opsForSet().members(indexKey);
            if (keys != null && !keys.isEmpty()) {
                keys.forEach(key -> stringRedisTemplate.delete(metadataKey(key)));
                stringRedisTemplate.delete(keys);
            }
            stringRedisTemplate.delete(indexKey);
        } catch (RedisConnectionFailureException e) {
            log.debug("media gallery Redis cache invalidation skipped: {}", e.getMessage());
        } catch (Exception e) {
            log.debug("media gallery Redis cache invalidation failed: {}", e.getMessage());
        }
    }

    private void index(String redisKey, Key key, Duration ttl) {
        String indexKey = deviceIndexKey(key.userId(), key.deviceId());
        stringRedisTemplate.opsForSet().add(indexKey, redisKey);
        stringRedisTemplate.expire(indexKey, ttl.plus(INDEX_TTL_PADDING));
    }

    private String cacheKey(Key key) {
        String hash = sha256(key.material() == null ? "" : key.material());
        return PREFIX + ":cache:" + key.userId() + ":" + key.deviceId() + ":" + key.kind() + ":" + hash;
    }

    private String metadataKey(String cacheKey) {
        return cacheKey + ":meta";
    }

    private String deviceIndexKey(UUID userId, UUID deviceId) {
        return PREFIX + ":device:" + userId + ":" + deviceId + ":keys";
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
