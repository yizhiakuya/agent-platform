package com.agentplatform.agent.ai;

import com.agentplatform.protocol.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PhotoToolArgsSanitizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PhotoToolArgsSanitizer sanitizer = new PhotoToolArgsSanitizer(mapper);

    @Test
    void dropsEmptyDateAndStringFiltersFromPhotoTools() {
        ObjectNode args = mapper.createObjectNode();
        args.put("query", "猫 cat");
        args.put("limit", 5);
        args.put("scan_limit", 80);
        args.put("name_contains", "");
        args.put("date_after", 0);
        args.put("date_before", 0);

        JsonNode out = sanitizer.before(
                UUID.randomUUID(),
                UUID.randomUUID(),
                spec("photos.semantic_candidates"),
                args,
                Map.of());

        assertEquals("猫 cat", out.path("query").asText());
        assertEquals(5, out.path("limit").asInt());
        assertEquals(80, out.path("scan_limit").asInt());
        assertFalse(out.has("name_contains"));
        assertFalse(out.has("date_after"));
        assertFalse(out.has("date_before"));
    }

    @Test
    void leavesNonPhotoToolsUntouched() {
        ObjectNode args = mapper.createObjectNode();
        args.put("date_after", 0);

        JsonNode out = sanitizer.before(
                UUID.randomUUID(),
                UUID.randomUUID(),
                spec("ui.tap"),
                args,
                Map.of());

        assertEquals(0, out.path("date_after").asInt());
    }

    @Test
    void dropsEmptyFiltersFromVideoListTool() {
        ObjectNode args = mapper.createObjectNode();
        args.put("limit", 6);
        args.put("name_contains", "");
        args.put("date_after", 0);
        args.put("date_before", 0);

        JsonNode out = sanitizer.before(
                UUID.randomUUID(),
                UUID.randomUUID(),
                spec("videos.list_recent"),
                args,
                Map.of());

        assertEquals(6, out.path("limit").asInt());
        assertFalse(out.has("name_contains"));
        assertFalse(out.has("date_after"));
        assertFalse(out.has("date_before"));
    }

    @Test
    void capsHeavyPhotoListLimits() {
        ObjectNode args = mapper.createObjectNode();
        args.put("limit", 30);

        JsonNode out = sanitizer.before(
                UUID.randomUUID(),
                UUID.randomUUID(),
                spec("photos.list_recent"),
                args,
                Map.of());

        assertEquals(8, out.path("limit").asInt());
    }

    @Test
    void capsRecentScreenshotLimits() {
        ObjectNode args = mapper.createObjectNode();
        args.put("limit", 20);

        JsonNode out = sanitizer.before(
                UUID.randomUUID(),
                UUID.randomUUID(),
                spec("photos.recent_screenshots"),
                args,
                Map.of());

        assertEquals(8, out.path("limit").asInt());
    }

    @Test
    void leavesVideoLimitUncapped() {
        ObjectNode args = mapper.createObjectNode();
        args.put("limit", 30);

        JsonNode out = sanitizer.before(
                UUID.randomUUID(),
                UUID.randomUUID(),
                spec("videos.list_recent"),
                args,
                Map.of());

        assertEquals(30, out.path("limit").asInt());
    }

    @Test
    void normalizesGalleryBrowseArgs() {
        ObjectNode args = mapper.createObjectNode();
        args.put("view", "category");
        args.put("category", "");
        args.put("bucket_id", "");
        args.put("limit", 120);

        JsonNode out = sanitizer.before(
                UUID.randomUUID(),
                UUID.randomUUID(),
                spec("media.gallery.browse"),
                args,
                Map.of());

        assertEquals("category", out.path("view").asText());
        assertEquals(80, out.path("limit").asInt());
        assertFalse(out.has("category"));
        assertFalse(out.has("bucket_id"));
    }

    private ToolSpec spec(String name) {
        return new ToolSpec(name, "test", mapper.createObjectNode(), false);
    }
}
