package com.agentplatform.agent.chat;

import com.agentplatform.api.chat.UpsertSessionArtifactRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Extracts lightweight working-set references from tool result JSON.
 */
@Component
public class ToolArtifactExtractor {

    private static final int MAX_ARTIFACTS_PER_RESULT = 8;

    private final ObjectMapper mapper;

    public ToolArtifactExtractor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<UpsertSessionArtifactRequest> extract(UUID sessionId,
                                                      UUID userId,
                                                      UUID messageId,
                                                      String tool,
                                                      JsonNode result) {
        if (sessionId == null || userId == null || result == null || result.isNull()) {
            return List.of();
        }
        List<UpsertSessionArtifactRequest> out = new ArrayList<>();
        if (isPhotoTool(tool)) {
            collectPhotoArray(out, sessionId, userId, messageId, tool, result.path("photos"));
            collectPhotoArray(out, sessionId, userId, messageId, tool, result.path("items"));
            collectSinglePhoto(out, sessionId, userId, messageId, tool, result, null);
        } else if ("ui.screen_capture".equals(tool)) {
            collectScreenCapture(out, sessionId, userId, messageId, tool, result);
        } else if ("ui.dump_tree".equals(tool)) {
            collectUiTree(out, sessionId, userId, messageId, tool, result);
        }
        return out.size() <= MAX_ARTIFACTS_PER_RESULT ? out : out.subList(0, MAX_ARTIFACTS_PER_RESULT);
    }

    private void collectScreenCapture(List<UpsertSessionArtifactRequest> out,
                                      UUID sessionId,
                                      UUID userId,
                                      UUID messageId,
                                      String tool,
                                      JsonNode result) {
        JsonNode capture = result.path("capture");
        JsonNode device = result.path("device");
        int width = firstInt(capture, "w", "width");
        int height = firstInt(capture, "h", "height");
        String source = firstText(result, "source");
        String key = "latest-screen";
        String title = width > 0 && height > 0
                ? "Screen capture " + width + "x" + height
                : "Screen capture";
        String summary = "Recent UI screenshot";
        if (source != null && !source.isBlank()) {
            summary += " source=" + source;
        }
        if (width > 0 && height > 0) {
            summary += " size=" + width + "x" + height;
        }

        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("tool", tool == null ? "" : tool);
        metadata.put("resultKind", "screen_capture");
        putIfText(metadata, "source", source);
        if (width > 0) metadata.put("captureWidth", width);
        if (height > 0) metadata.put("captureHeight", height);
        int deviceWidth = firstInt(device, "w", "width");
        int deviceHeight = firstInt(device, "h", "height");
        if (deviceWidth > 0) metadata.put("deviceWidth", deviceWidth);
        if (deviceHeight > 0) metadata.put("deviceHeight", deviceHeight);
        out.add(new UpsertSessionArtifactRequest(
                sessionId,
                userId,
                messageId,
                "screen",
                key,
                title,
                summary,
                metadata));
    }

    private void collectUiTree(List<UpsertSessionArtifactRequest> out,
                               UUID sessionId,
                               UUID userId,
                               UUID messageId,
                               String tool,
                               JsonNode result) {
        String packageName = firstText(result, "packageName", "package_name", "foregroundPackage", "foreground_package");
        String activityName = firstText(result, "activityName", "activity_name", "activity", "windowTitle", "window_title");
        String key = packageName == null || packageName.isBlank()
                ? "latest-ui-tree"
                : "latest-ui-tree:" + packageName;
        String title = packageName == null || packageName.isBlank()
                ? "UI tree"
                : "UI tree: " + packageName;
        StringBuilder summary = new StringBuilder("Recent accessibility tree");
        if (packageName != null && !packageName.isBlank()) {
            summary.append(" package=").append(packageName);
        }
        if (activityName != null && !activityName.isBlank()) {
            summary.append(" activity=").append(activityName);
        }
        int nodeCount = firstInt(result, "nodeCount", "node_count", "nodes");
        if (nodeCount > 0) {
            summary.append(" nodes=").append(nodeCount);
        }

        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("tool", tool == null ? "" : tool);
        metadata.put("resultKind", "ui_tree");
        putIfText(metadata, "packageName", packageName);
        putIfText(metadata, "activityName", activityName);
        if (nodeCount > 0) metadata.put("nodeCount", nodeCount);
        out.add(new UpsertSessionArtifactRequest(
                sessionId,
                userId,
                messageId,
                "ui_tree",
                key,
                title,
                summary.toString(),
                metadata));
    }

    private void collectPhotoArray(List<UpsertSessionArtifactRequest> out,
                                   UUID sessionId,
                                   UUID userId,
                                   UUID messageId,
                                   String tool,
                                   JsonNode photos) {
        if (!photos.isArray()) return;
        int rank = 1;
        for (JsonNode photo : photos) {
            collectSinglePhoto(out, sessionId, userId, messageId, tool, photo, rank++);
            if (out.size() >= MAX_ARTIFACTS_PER_RESULT) return;
        }
    }

    private void collectSinglePhoto(List<UpsertSessionArtifactRequest> out,
                                    UUID sessionId,
                                    UUID userId,
                                    UUID messageId,
                                    String tool,
                                    JsonNode photo,
                                    Integer resultRank) {
        String id = firstText(photo, "id", "asset_id", "photo_id", "mediaStoreId", "media_store_id");
        if (id == null || id.isBlank()) return;
        String title = firstText(photo, "name", "display_name", "filename", "bucketName", "bucket_name");
        Long dateTakenMs = firstLong(photo, "dateTakenMs", "date_taken_ms");
        String summary = photoSummary(title, dateTakenMs, firstText(photo, "mimeType", "mime_type"));
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("tool", tool == null ? "" : tool);
        metadata.put("photoId", id);
        if (resultRank != null) metadata.put("resultRank", resultRank);
        putIfText(metadata, "deviceId", firstText(photo, "deviceId", "device_id"));
        putIfText(metadata, "mediaStoreId", firstText(photo, "mediaStoreId", "media_store_id"));
        putIfText(metadata, "name", title);
        putIfText(metadata, "bucketName", firstText(photo, "bucketName", "bucket_name"));
        putIfText(metadata, "mimeType", firstText(photo, "mimeType", "mime_type"));
        if (dateTakenMs != null) metadata.put("dateTakenMs", dateTakenMs);
        out.add(new UpsertSessionArtifactRequest(
                sessionId,
                userId,
                messageId,
                "photo",
                id,
                title,
                summary,
                metadata));
    }

    private static boolean isPhotoTool(String tool) {
        return tool != null && tool.startsWith("photos.");
    }

    private static String firstText(JsonNode node, String... names) {
        if (node == null || !node.isObject()) return null;
        for (String name : names) {
            JsonNode v = node.path(name);
            if (v.isTextual() && !v.asText().isBlank()) return v.asText();
            if (v.isNumber()) return v.asText();
        }
        return null;
    }

    private static Long firstLong(JsonNode node, String... names) {
        if (node == null || !node.isObject()) return null;
        for (String name : names) {
            JsonNode v = node.path(name);
            if (v.isNumber()) return v.asLong();
            if (v.isTextual()) {
                try {
                    return Long.parseLong(v.asText());
                } catch (NumberFormatException ignored) {
                    // try next alias
                }
            }
        }
        return null;
    }

    private static int firstInt(JsonNode node, String... names) {
        if (node == null || !node.isObject()) return -1;
        for (String name : names) {
            JsonNode v = node.path(name);
            if (v.isInt() || v.isLong()) return v.asInt();
            if (v.isTextual()) {
                try {
                    return Integer.parseInt(v.asText());
                } catch (NumberFormatException ignored) {
                    // try next alias
                }
            }
            if (v.isArray()) return v.size();
        }
        return -1;
    }

    private static void putIfText(ObjectNode node, String key, String value) {
        if (value != null && !value.isBlank()) node.put(key, value);
    }

    private static String photoSummary(String title, Long dateTakenMs, String mimeType) {
        StringBuilder sb = new StringBuilder("Photo result");
        if (title != null && !title.isBlank()) sb.append(": ").append(title);
        if (dateTakenMs != null) sb.append(" taken_ms=").append(dateTakenMs);
        if (mimeType != null && !mimeType.isBlank()) sb.append(" mime=").append(mimeType);
        return sb.toString();
    }
}
