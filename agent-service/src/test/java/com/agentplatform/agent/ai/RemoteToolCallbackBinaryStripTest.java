package com.agentplatform.agent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.agentplatform.agent.client.DeviceToolDispatcher;
import com.agentplatform.protocol.ToolResult;
import com.agentplatform.protocol.ToolSpec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RemoteToolCallback#stripB64ForLlmAndCollect}.
 *
 * <p>The method is the single guard between device-tool JSON output and the
 * LLM context window: every {@code *_b64} field must be replaced with a short
 * placeholder so we don't blow the context with hundreds of KB of base64,
 * while the binary itself is captured into a {@link PendingImage} list so
 * the caller can re-attach it as a native multimodal {@code tool_result}
 * image block. Vision-priority logic ensures that when a tool returns both
 * a high-resolution {@code vision_b64} and a small {@code thumb_b64}, only
 * the vision copy is sent to the LLM.
 */
class RemoteToolCallbackBinaryStripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void replacesPlainB64FieldWithPlaceholderAndCollects() throws Exception {
        JsonNode in = mapper.readTree("{\"thumb_b64\":\"/9j/abc123xyz\",\"other\":\"keep\"}");
        List<PendingImage> imgs = new ArrayList<>();

        JsonNode out = RemoteToolCallback.stripB64ForLlmAndCollect(in, "", imgs);

        assertEquals("keep", out.get("other").asText(), "non-b64 fields untouched");
        assertTrue(out.get("thumb_b64").asText().startsWith("<binary"),
                "b64 field replaced with placeholder so the LLM doesn't see raw bytes");
        assertFalse(out.get("thumb_b64").asText().contains("/9j/"),
                "raw base64 must not survive in the JSON forwarded to the LLM");
        assertEquals(1, imgs.size(), "single b64 field → one PendingImage");
        assertEquals("image/jpeg", imgs.get(0).mimeType(),
                "JPEG magic '/9j/' should be sniffed");
        assertEquals("/9j/abc123xyz", imgs.get(0).b64(), "raw bytes preserved for native attachment");
    }

    @Test
    void visionPreemptsThumbWhenBothPresent() throws Exception {
        // Photos.get_full returns both — vision_b64 (2048px JPEG q85, for the
        // LLM) and thumb_b64 (256px JPEG q70, for the web bubble). The LLM
        // should only see the vision copy; thumb gets placeholdered but isn't
        // collected as a duplicate image (would double the context cost).
        JsonNode in = mapper.readTree(
                "{\"thumb_b64\":\"smallthumb\",\"vision_b64\":\"iVBORw0KGgoAAAA\"}");
        List<PendingImage> imgs = new ArrayList<>();

        JsonNode out = RemoteToolCallback.stripB64ForLlmAndCollect(in, "", imgs);

        assertTrue(out.get("thumb_b64").asText().contains("omitted"),
                "thumb has placeholder marking that vision sibling preferred");
        assertTrue(out.get("vision_b64").asText().contains("attached"),
                "vision has placeholder marking it was attached to the LLM");
        assertEquals(1, imgs.size(),
                "with vision sibling present, thumb must NOT be collected as a duplicate image");
        assertEquals("image/png", imgs.get(0).mimeType(),
                "PNG magic 'iVBORw0KGgo' should be sniffed");
        assertEquals("iVBORw0KGgoAAAA", imgs.get(0).b64());
    }

    @Test
    void thumbAloneStillCollected() throws Exception {
        // photos.list_recent only emits thumb_b64 (no vision). It should be
        // collected as a normal image — the vision-priority skip only fires
        // when a vision sibling exists in the same object.
        JsonNode in = mapper.readTree("{\"thumb_b64\":\"/9j/onlythumb\"}");
        List<PendingImage> imgs = new ArrayList<>();

        JsonNode out = RemoteToolCallback.stripB64ForLlmAndCollect(in, "", imgs);

        assertTrue(out.get("thumb_b64").asText().contains("attached"));
        assertEquals(1, imgs.size(), "no vision sibling → thumb gets collected");
    }

    @Test
    void recursesArraysAndNestedObjects() throws Exception {
        // photos.list_recent shape: {"photos":[{id, thumb_b64}, ...]}.
        JsonNode in = mapper.readTree(
                "{\"photos\":[" +
                "{\"id\":\"1\",\"thumb_b64\":\"aaa\"}," +
                "{\"id\":\"2\",\"thumb_b64\":\"bbb\"}" +
                "]}");
        List<PendingImage> imgs = new ArrayList<>();

        JsonNode out = RemoteToolCallback.stripB64ForLlmAndCollect(in, "", imgs);

        assertEquals(2, imgs.size(), "each array element's b64 collected");
        assertEquals("aaa", imgs.get(0).b64());
        assertEquals("bbb", imgs.get(1).b64());
        assertEquals("1", out.get("photos").get(0).get("id").asText(), "non-b64 sibling preserved");
        assertTrue(out.get("photos").get(0).get("thumb_b64").asText().startsWith("<binary"));
        assertTrue(out.get("photos").get(1).get("thumb_b64").asText().startsWith("<binary"));
    }

    @Test
    void emptyB64NotCollectedButStillPlaceholdered() throws Exception {
        // Defensive: if a tool returns an empty b64 we still scrub it from
        // the JSON, but we don't emit a 0-byte PendingImage that would just
        // waste an Anthropic content block slot.
        JsonNode in = mapper.readTree("{\"thumb_b64\":\"\"}");
        List<PendingImage> imgs = new ArrayList<>();

        JsonNode out = RemoteToolCallback.stripB64ForLlmAndCollect(in, "", imgs);

        assertEquals(0, imgs.size(), "empty b64 must not produce a PendingImage");
        assertTrue(out.get("thumb_b64").asText().contains("omitted"),
                "empty b64 is placeholdered (not left raw) so the LLM doesn't get a stray empty string");
    }

    @Test
    void nonB64FieldsLookingLikeB64FieldsAreLeftAlone() throws Exception {
        // Only fields whose name ends in "_b64" are stripped. A field called
        // "description_64" or "image_url" must pass through verbatim so we
        // don't accidentally drop tool metadata.
        JsonNode in = mapper.readTree(
                "{\"description\":\"a JPEG\",\"image_url\":\"http://x/img.jpg\",\"id\":\"1\"}");
        List<PendingImage> imgs = new ArrayList<>();

        JsonNode out = RemoteToolCallback.stripB64ForLlmAndCollect(in, "", imgs);

        assertEquals(0, imgs.size());
        assertEquals("a JPEG", out.get("description").asText());
        assertEquals("http://x/img.jpg", out.get("image_url").asText());
    }

    @Test
    void uploadedImageUrlIsFetchedAndCollectedWhenVisionEnabled() throws Exception {
        DeviceToolDispatcher dispatcher = org.mockito.Mockito.mock(DeviceToolDispatcher.class);
        UUID userId = UUID.randomUUID();
        org.mockito.Mockito.when(dispatcher.dispatch(
                        org.mockito.Mockito.any(), org.mockito.Mockito.eq(userId),
                        org.mockito.Mockito.eq("photos.list_recent"), org.mockito.Mockito.any()))
                .thenReturn(ToolResult.ok(mapper.readTree(
                        "{\"photos\":[{\"id\":\"1\",\"image_url\":\"/api/uploads/photos/asset-1\"}]}")));
        org.mockito.Mockito.when(dispatcher.fetchUploadAsset(userId, "/api/uploads/photos/asset-1"))
                .thenReturn(Optional.of(new DeviceToolDispatcher.FetchedAsset(
                        "image/jpeg", "jpeg-bytes".getBytes(StandardCharsets.UTF_8))));

        RemoteToolCallback callback = new RemoteToolCallback(
                UUID.randomUUID(),
                userId,
                new ToolSpec("photos.list_recent", "list", mapper.createObjectNode(), false),
                dispatcher,
                mapper,
                List.of(),
                null,
                true);

        ExecutionResult result = callback.executeJsonToolUse(mapper.createObjectNode(), userId, UUID.randomUUID(), null);

        assertEquals(1, result.images().size());
        assertEquals("image/jpeg", result.images().get(0).mimeType());
        assertEquals("anBlZy1ieXRlcw==", result.images().get(0).b64());
        assertTrue(result.jsonText().contains("\"_vision_attached_count\":1"));
    }

    @Test
    void uploadedImageCollectionIsCappedAndSkipsAlbumCoverUrls() throws Exception {
        DeviceToolDispatcher dispatcher = org.mockito.Mockito.mock(DeviceToolDispatcher.class);
        UUID userId = UUID.randomUUID();
        StringBuilder photos = new StringBuilder("{\"photos\":[");
        for (int i = 1; i <= 8; i++) {
            if (i > 1) photos.append(',');
            photos.append("{\"id\":\"").append(i).append("\",\"image_url\":\"/api/uploads/photos/asset-")
                    .append(i).append("\"}");
            org.mockito.Mockito.when(dispatcher.fetchUploadAsset(userId, "/api/uploads/photos/asset-" + i))
                    .thenReturn(Optional.of(new DeviceToolDispatcher.FetchedAsset(
                            "image/jpeg", ("jpeg-" + i).getBytes(StandardCharsets.UTF_8))));
        }
        photos.append("],\"albums\":[{\"cover_image_url\":\"/api/uploads/photos/cover-1\"}]}");
        org.mockito.Mockito.when(dispatcher.dispatch(
                        org.mockito.Mockito.any(), org.mockito.Mockito.eq(userId),
                        org.mockito.Mockito.eq("photos.list_recent"), org.mockito.Mockito.any()))
                .thenReturn(ToolResult.ok(mapper.readTree(photos.toString())));

        RemoteToolCallback callback = new RemoteToolCallback(
                UUID.randomUUID(),
                userId,
                new ToolSpec("photos.list_recent", "list", mapper.createObjectNode(), false),
                dispatcher,
                mapper,
                List.of(),
                null,
                true);

        ExecutionResult result = callback.executeJsonToolUse(mapper.createObjectNode(), userId, UUID.randomUUID(), null);

        assertEquals(6, result.images().size(), "uploaded image attachments are capped per tool result");
        assertTrue(result.jsonText().contains("\"_vision_attached_count\":6"));
        org.mockito.Mockito.verify(dispatcher, org.mockito.Mockito.never())
                .fetchUploadAsset(userId, "/api/uploads/photos/cover-1");
    }
}
