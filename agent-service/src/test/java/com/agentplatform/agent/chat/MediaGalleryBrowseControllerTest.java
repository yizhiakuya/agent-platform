package com.agentplatform.agent.chat;

import com.agentplatform.agent.client.DeviceHubClient;
import com.agentplatform.agent.client.DeviceToolDispatcher;
import com.agentplatform.api.hub.OnlineDeviceDto;
import com.agentplatform.protocol.ToolManifest;
import com.agentplatform.protocol.ToolResult;
import com.agentplatform.protocol.ToolSpec;
import com.agentplatform.security.Principal;
import com.agentplatform.security.PrincipalContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaGalleryBrowseControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DeviceHubClient deviceHubClient = mock(DeviceHubClient.class);
    private final DeviceToolDispatcher dispatcher = mock(DeviceToolDispatcher.class);
    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID deviceId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final InMemoryMediaGalleryCache cache = new InMemoryMediaGalleryCache();
    private final MediaGalleryBrowseController controller =
            new MediaGalleryBrowseController(deviceHubClient, dispatcher, mapper, cache);

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void browseReusesShortCacheAndTrashInvalidatesDeviceCache() {
        PrincipalContext.set(new Principal(Principal.TYPE_USER, userId.toString(), userId.toString(), "jti"));
        when(deviceHubClient.listOnlineByUser(userId)).thenReturn(List.of(device()));

        ObjectNode args = mapper.createObjectNode();
        args.put("view", "category");
        args.put("category", "camera");

        ObjectNode first = galleryValue("first");
        ObjectNode second = galleryValue("second");
        when(dispatcher.dispatch(eq(deviceId), eq(userId), eq("media.gallery.browse"), any()))
                .thenReturn(ToolResult.ok(first))
                .thenReturn(ToolResult.ok(second));
        when(dispatcher.dispatch(eq(deviceId), eq(userId), eq("media.gallery.trash"), any()))
                .thenReturn(ToolResult.ok(mapper.createObjectNode().put("ok", true).put("affected_count", 1)));

        MediaGalleryBrowseController.MediaGalleryBrowseRequest request =
                new MediaGalleryBrowseController.MediaGalleryBrowseRequest(args, deviceId);
        assertThat(controller.browse(request).path("marker").asText()).isEqualTo("first");
        assertThat(controller.browse(request).path("marker").asText()).isEqualTo("first");
        verify(dispatcher, times(1)).dispatch(eq(deviceId), eq(userId), eq("media.gallery.browse"), any());

        ObjectNode trashArgs = mapper.createObjectNode();
        trashArgs.putArray("items").addObject().put("media_type", "photo").put("id", "101");
        controller.trash(new MediaGalleryBrowseController.MediaGalleryTrashRequest(trashArgs, deviceId));

        assertThat(controller.browse(request).path("marker").asText()).isEqualTo("second");
        verify(dispatcher, times(2)).dispatch(eq(deviceId), eq(userId), eq("media.gallery.browse"), any());
    }

    @Test
    void originalCacheUsesMediaVersion() {
        PrincipalContext.set(new Principal(Principal.TYPE_USER, userId.toString(), userId.toString(), "jti"));
        when(deviceHubClient.listOnlineByUser(userId)).thenReturn(List.of(device()));

        ObjectNode first = mapper.createObjectNode().put("image_url", "/api/uploads/photos/one");
        ObjectNode second = mapper.createObjectNode().put("image_url", "/api/uploads/photos/two");
        when(dispatcher.dispatch(eq(deviceId), eq(userId), eq("photos.get_full"), any()))
                .thenReturn(ToolResult.ok(first))
                .thenReturn(ToolResult.ok(second));

        MediaGalleryBrowseController.MediaGalleryOriginalRequest v1 =
                new MediaGalleryBrowseController.MediaGalleryOriginalRequest("101", "photo", 2048, deviceId, 10L, 100L);
        MediaGalleryBrowseController.MediaGalleryOriginalRequest v2 =
                new MediaGalleryBrowseController.MediaGalleryOriginalRequest("101", "photo", 2048, deviceId, 11L, 100L);

        assertThat(controller.original(v1).path("image_url").asText()).endsWith("/one");
        assertThat(controller.original(v1).path("image_url").asText()).endsWith("/one");
        assertThat(controller.original(v2).path("image_url").asText()).endsWith("/two");
        verify(dispatcher, times(2)).dispatch(eq(deviceId), eq(userId), eq("photos.get_full"), any());
    }

    private OnlineDeviceDto device() {
        return new OnlineDeviceDto(deviceId, userId, new ToolManifest(List.of(
                tool("media.gallery.browse"),
                tool("media.gallery.thumbnail"),
                tool("media.gallery.trash"),
                tool("photos.get_full")
        )), OffsetDateTime.now());
    }

    private ToolSpec tool(String name) {
        return new ToolSpec(name, name, mapper.createObjectNode(), false);
    }

    private ObjectNode galleryValue(String marker) {
        return mapper.createObjectNode()
                .put("ok", true)
                .put("marker", marker)
                .put("thumb_url", "/api/chat/media-gallery/thumbnail?mediaType=photo&id=101&maxDim=256");
    }

    private static class InMemoryMediaGalleryCache implements MediaGalleryCache {
        private final Map<Key, JsonNode> json = new ConcurrentHashMap<>();
        private final Map<Key, Thumbnail> thumbnails = new ConcurrentHashMap<>();

        @Override
        public Key key(UUID userId, UUID deviceId, String kind, String material) {
            return new Key(userId, deviceId, kind, material);
        }

        @Override
        public Optional<JsonNode> getJson(Key key) {
            JsonNode value = json.get(key);
            return value == null ? Optional.empty() : Optional.of(value.deepCopy());
        }

        @Override
        public void putJson(Key key, JsonNode value, Duration ttl) {
            json.put(key, value.deepCopy());
        }

        @Override
        public Optional<Thumbnail> getThumbnail(Key key) {
            return Optional.ofNullable(thumbnails.get(key));
        }

        @Override
        public void putThumbnail(Key key, byte[] bytes, String contentType, Duration ttl) {
            thumbnails.put(key, new Thumbnail(bytes, contentType));
        }

        @Override
        public void invalidateDevice(UUID userId, UUID deviceId) {
            json.keySet().removeIf(key -> key.userId().equals(userId) && key.deviceId().equals(deviceId));
            thumbnails.keySet().removeIf(key -> key.userId().equals(userId) && key.deviceId().equals(deviceId));
        }
    }
}
