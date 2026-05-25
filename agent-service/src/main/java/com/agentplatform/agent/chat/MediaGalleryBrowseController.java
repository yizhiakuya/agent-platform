package com.agentplatform.agent.chat;

import com.agentplatform.agent.client.DeviceHubClient;
import com.agentplatform.agent.client.DeviceToolDispatcher;
import com.agentplatform.api.hub.OnlineDeviceDto;
import com.agentplatform.protocol.ToolManifest;
import com.agentplatform.protocol.ToolResult;
import com.agentplatform.protocol.ToolSpec;
import com.agentplatform.security.PrincipalContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat/media-gallery")
public class MediaGalleryBrowseController {

    private static final String BROWSE_TOOL = "media.gallery.browse";
    private static final String THUMBNAIL_TOOL = "media.gallery.thumbnail";
    private static final String PHOTO_ORIGINAL_TOOL = "photos.get_full";
    private static final String TRASH_TOOL = "media.gallery.trash";
    private static final String RESTORE_TOOL = "media.gallery.restore";
    private static final String MEDIA_TYPE_PHOTO = "photo";
    private static final String ERROR_FIELD = "error";
    private static final String MESSAGE_FIELD = "message";
    private static final int DEFAULT_THUMBNAIL_MAX_DIM = 256;
    private static final int MIN_THUMBNAIL_MAX_DIM = 128;
    private static final int MAX_THUMBNAIL_MAX_DIM = 640;
    private static final Duration BROWSE_CACHE_TTL = Duration.ofSeconds(15);
    private static final Duration ORIGINAL_VERSIONED_CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration ORIGINAL_UNVERSIONED_CACHE_TTL = Duration.ofSeconds(30);
    private static final Duration THUMBNAIL_VERSIONED_CACHE_TTL = Duration.ofHours(1);
    private static final Duration THUMBNAIL_UNVERSIONED_CACHE_TTL = Duration.ofSeconds(30);

    private final DeviceHubClient deviceHubClient;
    private final DeviceToolDispatcher dispatcher;
    private final ObjectMapper mapper;
    private final MediaGalleryCache cache;

    public MediaGalleryBrowseController(DeviceHubClient deviceHubClient,
                                        DeviceToolDispatcher dispatcher,
                                        ObjectMapper mapper,
                                        MediaGalleryCache cache) {
        this.deviceHubClient = deviceHubClient;
        this.dispatcher = dispatcher;
        this.mapper = mapper;
        this.cache = cache;
    }

    @PostMapping("/browse")
    public JsonNode browse(@RequestBody MediaGalleryBrowseRequest request) {
        UUID userId = PrincipalContext.requireUserId();
        JsonNode args = normalizeArgs(request == null ? null : request.args());
        OnlineDeviceDto device = resolveDevice(userId, request == null ? null : request.deviceId());
        ensureTool(device, BROWSE_TOOL, "当前设备还没有上报相册浏览工具，请重连或更新 APK");

        MediaGalleryCache.Key cacheKey = cache.key(userId, device.deviceId(), "browse", canonicalJson(args));
        JsonNode cached = cache.getJson(cacheKey).orElse(null);
        if (cached != null) return cached.deepCopy();

        JsonNode value = dispatch(device, userId, BROWSE_TOOL, args, "设备没有返回相册结果", "相册工具调用失败");
        attachDeviceIdToThumbnailUrls(value, device.deviceId());
        cache.putJson(cacheKey, value, BROWSE_CACHE_TTL);
        return value;
    }

    @GetMapping("/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@RequestParam("id") String id,
                                            @RequestParam(value = "mediaType", defaultValue = "photo") String mediaType,
                                            @RequestParam(value = "maxDim", required = false) Integer maxDim,
                                            @RequestParam(value = "v", required = false) String version,
                                            @RequestParam(value = "deviceId", required = false) UUID deviceId) {
        UUID userId = PrincipalContext.requireUserId();
        String cleanId = id == null ? "" : id.trim();
        if (cleanId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "媒体 id 不能为空");
        }
        String cleanMediaType = normalizeMediaType(mediaType);
        int cleanMaxDim = clamp(maxDim == null ? DEFAULT_THUMBNAIL_MAX_DIM : maxDim,
                MIN_THUMBNAIL_MAX_DIM, MAX_THUMBNAIL_MAX_DIM);

        OnlineDeviceDto device = resolveDevice(userId, deviceId);
        ensureTool(device, THUMBNAIL_TOOL, "当前设备还没有上报相册缩略图工具，请重连或更新 APK");
        String cacheVersion = normalizeCacheVersion(version);
        MediaGalleryCache.Key cacheKey = cache.key(
                userId,
                device.deviceId(),
                "thumbnail",
                cleanMediaType + "|" + cleanId + "|" + cleanMaxDim + "|" + cacheVersionKey(cacheVersion));
        MediaGalleryCache.Thumbnail cached = cache.getThumbnail(cacheKey).orElse(null);
        if (cached != null) {
            return thumbnailResponse(cached.bytes(), cached.contentType(), cacheVersion);
        }

        ObjectNode args = mapper.createObjectNode();
        args.put("media_type", cleanMediaType);
        args.put("id", cleanId);
        args.put("max_dim", cleanMaxDim);
        JsonNode value = dispatch(device, userId, THUMBNAIL_TOOL, args, "设备没有返回缩略图结果", "缩略图工具调用失败");
        String imageUrl = firstText(value, "thumb_url", "image_url", "asset_url", "url");
        if (imageUrl == null || !imageUrl.startsWith("/api/uploads/photos/")) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "设备没有返回可读取的缩略图地址");
        }

        DeviceToolDispatcher.FetchedAsset asset = dispatcher.fetchUploadAsset(userId, imageUrl)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "缩略图资产读取失败"));
        cache.putThumbnail(cacheKey, asset.bytes(), asset.contentType(), thumbnailCacheTtl(cacheVersion));
        return thumbnailResponse(asset.bytes(), asset.contentType(), cacheVersion);
    }

    @PostMapping("/original")
    public JsonNode original(@RequestBody MediaGalleryOriginalRequest request) {
        UUID userId = PrincipalContext.requireUserId();
        if (request == null || request.id() == null || request.id().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "媒体 id 不能为空");
        }
        String mediaType = request.mediaType() == null ? MEDIA_TYPE_PHOTO : request.mediaType().trim().toLowerCase(Locale.ROOT);
        if (!MEDIA_TYPE_PHOTO.equals(mediaType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前只支持照片原图预览");
        }
        int maxDim = request.maxDim() == null ? 2048 : Math.max(512, Math.min(2048, request.maxDim()));
        ObjectNode args = mapper.createObjectNode();
        args.put("id", request.id().trim());
        args.put("max_dim", maxDim);

        OnlineDeviceDto device = resolveDevice(userId, request.deviceId());
        ensureTool(device, PHOTO_ORIGINAL_TOOL, "当前设备还没有上报照片原图工具，请重连或更新 APK");
        String cacheVersion = mediaVersion(request.dateModifiedSec(), request.sizeBytes());
        MediaGalleryCache.Key cacheKey = cache.key(
                userId,
                device.deviceId(),
                "original",
                mediaType + "|" + request.id().trim() + "|" + maxDim + "|" + cacheVersionKey(cacheVersion));
        JsonNode cached = cache.getJson(cacheKey).orElse(null);
        if (cached != null) return cached.deepCopy();

        JsonNode value = dispatch(device, userId, PHOTO_ORIGINAL_TOOL, args, "设备没有返回原图结果", "原图工具调用失败");
        Duration ttl = cacheVersion == null ? ORIGINAL_UNVERSIONED_CACHE_TTL : ORIGINAL_VERSIONED_CACHE_TTL;
        cache.putJson(cacheKey, value, ttl);
        return value;
    }

    @PostMapping("/trash")
    public JsonNode trash(@RequestBody MediaGalleryTrashRequest request) {
        UUID userId = PrincipalContext.requireUserId();
        if (request == null || request.args() == null || !request.args().isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "删除参数必须是 JSON object");
        }
        OnlineDeviceDto device = resolveDevice(userId, request.deviceId());
        ensureTool(device, TRASH_TOOL, "当前设备还没有上报相册删除工具，请重连或更新 APK");
        JsonNode value = dispatch(device, userId, TRASH_TOOL, request.args(), "设备没有返回删除结果", "相册删除工具调用失败");
        cache.invalidateDevice(userId, device.deviceId());
        return value;
    }

    @PostMapping("/restore")
    public JsonNode restore(@RequestBody MediaGalleryRestoreRequest request) {
        UUID userId = PrincipalContext.requireUserId();
        if (request == null || request.args() == null || !request.args().isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "恢复参数必须是 JSON object");
        }
        OnlineDeviceDto device = resolveDevice(userId, request.deviceId());
        ensureTool(device, RESTORE_TOOL, "当前设备还没有上报相册恢复工具，请重连或更新 APK");
        JsonNode value = dispatch(device, userId, RESTORE_TOOL, request.args(), "设备没有返回恢复结果", "相册恢复工具调用失败");
        cache.invalidateDevice(userId, device.deviceId());
        return value;
    }

    private JsonNode normalizeArgs(JsonNode args) {
        if (args == null || args.isNull() || args.isMissingNode()) {
            ObjectNode obj = mapper.createObjectNode();
            obj.put("view", "albums");
            return obj;
        }
        if (!args.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "相册浏览参数必须是 JSON object");
        }
        return args;
    }

    private OnlineDeviceDto resolveDevice(UUID userId, UUID preferredDeviceId) {
        List<OnlineDeviceDto> online = deviceHubClient.listOnlineByUser(userId);
        if (online == null || online.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "没有在线设备，无法打开手机相册");
        }
        if (preferredDeviceId == null) {
            return online.get(0);
        }
        return online.stream()
                .filter(device -> preferredDeviceId.equals(device.deviceId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "选择的设备不在线"));
    }

    private void ensureTool(OnlineDeviceDto device, String toolName, String missingMessage) {
        ToolManifest manifest = device.manifest();
        List<ToolSpec> tools = manifest == null ? List.of() : manifest.tools();
        boolean supported = tools.stream().anyMatch(tool -> toolName.equals(tool.name()));
        if (!supported) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, missingMessage);
        }
    }

    private JsonNode dispatch(OnlineDeviceDto device,
                              UUID userId,
                              String toolName,
                              JsonNode args,
                              String emptyMessage,
                              String errorMessage) {
        ToolResult result = dispatcher.dispatch(device.deviceId(), userId, toolName, args);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, emptyMessage);
        }
        if (result.hasError()) {
            String message = result.error() == null ? errorMessage : result.error().message();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
        }
        JsonNode value = result.value() == null ? mapper.createObjectNode() : result.value();
        if (value.path("ok").isBoolean() && !value.path("ok").asBoolean()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, toolErrorMessage(value, errorMessage));
        }
        return value;
    }

    private String toolErrorMessage(JsonNode value, String fallback) {
        JsonNode detail = value.path("error_detail");
        if (detail.path(MESSAGE_FIELD).isTextual() && !detail.path(MESSAGE_FIELD).asText().isBlank()) {
            return detail.path(MESSAGE_FIELD).asText();
        }
        if (value.path(ERROR_FIELD).isTextual() && !value.path(ERROR_FIELD).asText().isBlank()) {
            return value.path(ERROR_FIELD).asText();
        }
        return fallback;
    }

    private String normalizeMediaType(String mediaType) {
        String value = mediaType == null ? MEDIA_TYPE_PHOTO : mediaType.trim().toLowerCase(Locale.ROOT);
        if ("image".equals(value)) return MEDIA_TYPE_PHOTO;
        if (!MEDIA_TYPE_PHOTO.equals(value) && !"video".equals(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mediaType 只支持 photo 或 video");
        }
        return value;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String firstText(JsonNode node, String... keys) {
        if (node == null || !node.isObject()) return null;
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private MediaType parseMediaType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType == null || contentType.isBlank() ? "image/jpeg" : contentType);
        } catch (Exception ignored) {
            return MediaType.IMAGE_JPEG;
        }
    }

    private ResponseEntity<byte[]> thumbnailResponse(byte[] bytes, String contentType, String cacheVersion) {
        MediaType mediaType = parseMediaType(contentType);
        Duration ttl = thumbnailCacheTtl(cacheVersion);
        return ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(ttl).cachePrivate())
                .contentType(mediaType)
                .contentLength(bytes.length)
                .body(bytes);
    }

    private Duration thumbnailCacheTtl(String cacheVersion) {
        return cacheVersion == null ? THUMBNAIL_UNVERSIONED_CACHE_TTL : THUMBNAIL_VERSIONED_CACHE_TTL;
    }

    private String canonicalJson(JsonNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ignored) {
            return value == null ? "null" : value.toString();
        }
    }

    private String mediaVersion(Long dateModifiedSec, Long sizeBytes) {
        Long modified = positiveLong(dateModifiedSec);
        Long size = positiveLong(sizeBytes);
        if (modified == null && size == null) return null;
        return "m=" + (modified == null ? "" : modified) + ";s=" + (size == null ? "" : size);
    }

    private Long positiveLong(Long value) {
        return value == null || value <= 0 ? null : value;
    }

    private String normalizeCacheVersion(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.length() > 96 ? trimmed.substring(0, 96) : trimmed;
    }

    private String cacheVersionKey(String cacheVersion) {
        return cacheVersion == null ? "unversioned" : cacheVersion;
    }

    private void attachDeviceIdToThumbnailUrls(JsonNode node, UUID deviceId) {
        if (node == null || deviceId == null) return;
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            appendDeviceId(obj, "thumb_url", deviceId);
            appendDeviceId(obj, "cover_thumb_url", deviceId);
            node.fields().forEachRemaining(entry -> attachDeviceIdToThumbnailUrls(entry.getValue(), deviceId));
        } else if (node.isArray()) {
            node.forEach(child -> attachDeviceIdToThumbnailUrls(child, deviceId));
        }
    }

    private void appendDeviceId(ObjectNode obj, String field, UUID deviceId) {
        JsonNode value = obj.path(field);
        if (!value.isTextual()) return;
        String url = value.asText();
        if (!url.startsWith("/api/chat/media-gallery/thumbnail?") || url.contains("deviceId=")) {
            return;
        }
        obj.put(field, url + "&deviceId=" + deviceId);
    }

    public record MediaGalleryBrowseRequest(JsonNode args, UUID deviceId) {}
    public record MediaGalleryOriginalRequest(String id, String mediaType, Integer maxDim, UUID deviceId,
                                              Long dateModifiedSec, Long sizeBytes) {}
    public record MediaGalleryTrashRequest(JsonNode args, UUID deviceId) {}
    public record MediaGalleryRestoreRequest(JsonNode args, UUID deviceId) {}
}
