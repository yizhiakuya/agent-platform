package com.agentplatform.agent.chat;

import com.agentplatform.api.chat.UpsertSessionArtifactRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ToolArtifactExtractorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolArtifactExtractor extractor = new ToolArtifactExtractor(mapper);

    @Test
    void extractsPhotoArtifactsFromSearchResult() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        var result = mapper.readTree("""
                {
                  "photos": [
                    {
                      "id": "asset-1",
                      "deviceId": "device-1",
                      "name": "cat.jpg",
                      "bucketName": "Camera",
                      "mimeType": "image/jpeg",
                      "dateTakenMs": 1710000000000,
                      "thumb_b64": "abc"
                    }
                  ]
                }
                """);

        List<UpsertSessionArtifactRequest> artifacts =
                extractor.extract(sessionId, userId, messageId, "photos.semantic_search", result);

        assertThat(artifacts).hasSize(1);
        UpsertSessionArtifactRequest a = artifacts.get(0);
        assertThat(a.sessionId()).isEqualTo(sessionId);
        assertThat(a.userId()).isEqualTo(userId);
        assertThat(a.messageId()).isEqualTo(messageId);
        assertThat(a.artifactType()).isEqualTo("photo");
        assertThat(a.artifactKey()).isEqualTo("asset-1");
        assertThat(a.title()).isEqualTo("cat.jpg");
        assertThat(a.summary()).contains("cat.jpg").contains("1710000000000");
        assertThat(a.metadata().path("thumb_b64").isMissingNode()).isTrue();
        assertThat(a.metadata().path("photoId").asText()).isEqualTo("asset-1");
        assertThat(a.metadata().path("resultRank").asInt()).isEqualTo(1);
    }

    @Test
    void extractsScreenArtifactFromCaptureResult() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        var result = mapper.readTree("""
                {
                  "vision_b64": "abc",
                  "source": "accessibility",
                  "capture": {"w": 1220, "h": 2712},
                  "device": {"w": 1220, "h": 2712}
                }
                """);

        List<UpsertSessionArtifactRequest> artifacts =
                extractor.extract(sessionId, userId, messageId, "ui.screen_capture", result);

        assertThat(artifacts).hasSize(1);
        UpsertSessionArtifactRequest a = artifacts.get(0);
        assertThat(a.artifactType()).isEqualTo("screen");
        assertThat(a.artifactKey()).isEqualTo("latest-screen");
        assertThat(a.title()).contains("1220x2712");
        assertThat(a.summary()).contains("accessibility").contains("1220x2712");
        assertThat(a.metadata().path("vision_b64").isMissingNode()).isTrue();
        assertThat(a.metadata().path("captureWidth").asInt()).isEqualTo(1220);
        assertThat(a.metadata().path("captureHeight").asInt()).isEqualTo(2712);
    }

    @Test
    void extractsUiTreeArtifactFromDumpTreeResult() throws Exception {
        var result = mapper.readTree("""
                {
                  "packageName": "com.sankuai.meituan.takeoutnew",
                  "activityName": "MainActivity",
                  "nodes": [{"text": "拼好饭"}, {"text": "饮品甜点"}]
                }
                """);

        List<UpsertSessionArtifactRequest> artifacts =
                extractor.extract(UUID.randomUUID(), UUID.randomUUID(), null, "ui.dump_tree", result);

        assertThat(artifacts).hasSize(1);
        UpsertSessionArtifactRequest a = artifacts.get(0);
        assertThat(a.artifactType()).isEqualTo("ui_tree");
        assertThat(a.artifactKey()).isEqualTo("latest-ui-tree:com.sankuai.meituan.takeoutnew");
        assertThat(a.summary()).contains("com.sankuai.meituan.takeoutnew").contains("nodes=2");
        assertThat(a.metadata().path("nodeCount").asInt()).isEqualTo(2);
    }

    @Test
    void extractsPhotoAndVideoArtifactsFromGalleryBrowseResult() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        var result = mapper.readTree("""
                {
                  "tool": "media.gallery.browse",
                  "items": [
                    {
                      "media_type": "photo",
                      "id": "101",
                      "name": "IMG_101.jpg",
                      "bucket_name": "Camera",
                      "media_ref": "media://photo/101",
                      "date_taken_ms": 1710000000000
                    },
                    {
                      "media_type": "video",
                      "id": "202",
                      "name": "VID_202.mp4",
                      "bucket_name": "Camera",
                      "media_ref": "media://video/202",
                      "duration_ms": 9000
                    }
                  ]
                }
                """);

        List<UpsertSessionArtifactRequest> artifacts =
                extractor.extract(sessionId, userId, messageId, "media.gallery.browse", result);

        assertThat(artifacts).hasSize(2);
        assertThat(artifacts.get(0).artifactType()).isEqualTo("photo");
        assertThat(artifacts.get(0).artifactKey()).isEqualTo("101");
        assertThat(artifacts.get(0).metadata().path("photoId").asText()).isEqualTo("101");
        assertThat(artifacts.get(1).artifactType()).isEqualTo("video");
        assertThat(artifacts.get(1).artifactKey()).isEqualTo("202");
        assertThat(artifacts.get(1).metadata().path("videoId").asText()).isEqualTo("202");
        assertThat(artifacts.get(1).metadata().path("mediaType").asText()).isEqualTo("video");
        assertThat(artifacts.get(1).metadata().path("durationMs").asLong()).isEqualTo(9000);
    }

    @Test
    void ignoresUnknownNonPhotoTools() throws Exception {
        var result = mapper.readTree("{\"id\":\"asset-1\"}");

        assertThat(extractor.extract(UUID.randomUUID(), UUID.randomUUID(), null, "ui.tap", result))
                .isEmpty();
    }
}
