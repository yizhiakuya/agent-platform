package com.agentplatform.agent.ai;

import com.agentplatform.agent.client.InternalChatFeignClient;
import com.agentplatform.agent.client.DeviceToolDispatcher;
import com.agentplatform.agent.config.AgentProperties;
import com.agentplatform.api.chat.PhotoAssetSearchRequest;
import com.agentplatform.api.chat.PhotoAssetSearchResult;
import com.agentplatform.protocol.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticPhotoSearchFormattingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fallbackRanksByLocalScoreWhenEmbeddingUnavailable() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("scanned", 2);
        var photos = mapper.createArrayNode();
        ObjectNode low = mapper.createObjectNode();
        low.put("id", "1");
        low.put("name", "plain.jpg");
        low.put("local_score", 0);
        low.put("thumb_b64", "/9j/low");
        ObjectNode high = mapper.createObjectNode();
        high.put("id", "2");
        high.put("name", "chat.jpg");
        high.put("local_score", 5);
        high.put("thumb_b64", "/9j/high");
        photos.add(low);
        photos.add(high);
        root.set("photos", photos);

        ObjectNode out = callbackShell().fallbackResult(
                "微信聊天", root, photos, 2, "embedding_failed");

        assertEquals("local_text_visual_fallback", out.path("semantic_engine").asText());
        assertEquals("2", out.path("photos").get(0).path("id").asText());
        assertEquals("1", out.path("photos").get(1).path("id").asText());
        assertEquals(2, out.path("count").asInt());
    }

    @Test
    void fallbackPromotesGenericVisualLabelMatches() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("scanned", 2);
        var photos = mapper.createArrayNode();
        ObjectNode textOnly = mapper.createObjectNode();
        textOnly.put("id", "text");
        textOnly.put("name", "ocr-heavy.jpg");
        textOnly.put("local_score", 20);
        textOnly.put("date_taken_ms", 1_000L);
        textOnly.set("visual_labels", mapper.createArrayNode().add("screenshot").add("text"));
        ObjectNode visualHit = mapper.createObjectNode();
        visualHit.put("id", "visual");
        visualHit.put("name", "restaurant.jpg");
        visualHit.put("local_score", 0);
        visualHit.put("date_taken_ms", 2_000L);
        visualHit.set("visual_labels", mapper.createArrayNode().add("menu").add("food"));
        photos.add(textOnly);
        photos.add(visualHit);
        root.set("photos", photos);

        ObjectNode out = callbackShell().fallbackResult(
                "菜单照片", root, photos, 2, "embedding_failed");

        assertEquals("visual", out.path("photos").get(0).path("id").asText());
        assertTrue(out.path("photos").get(0).path("server_visual_score").asDouble() > 0.0);
    }

    @Test
    void visualLabelScoreUsesGenericAliasExpansion() {
        var labels = mapper.createArrayNode().add("menu").add("food");

        float score = SemanticPhotoSearchCallback.visualLabelScore(
                SemanticPhotoSearchCallback.tokenize("菜单那张图"), labels);

        assertTrue(score > 0.0f);
    }

    @Test
    void tokenizeKeepsUsefulSingleChineseVisualTermsButDropsPhotoNoise() {
        var terms = SemanticPhotoSearchCallback.tokenize("找花的照片");

        assertTrue(terms.contains("花"));
        assertFalse(terms.contains("照"));
        assertFalse(terms.contains("片"));
    }

    @Test
    void textOrVisionStripsBase64AndCollectsImages() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        var photos = mapper.createArrayNode();
        ObjectNode photo = mapper.createObjectNode();
        photo.put("id", "1");
        photo.put("thumb_b64", "/9j/thumb");
        photos.add(photo);
        root.set("photos", photos);

        ExecutionResult out = callbackShell().textOrVision(root);

        assertEquals(1, out.images().size());
        assertFalse(out.jsonText().contains("/9j/thumb"));
        assertTrue(out.jsonText().contains("<binary"));
    }

    @Test
    void candidateReviewResultIsMarkedCandidateOnly() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("candidate_only", true);
        root.put("requested_limit", 1);
        var photos = mapper.createArrayNode();
        for (int i = 0; i < 2; i++) {
            ObjectNode photo = mapper.createObjectNode();
            photo.put("id", String.valueOf(i + 1));
            photo.put("candidate_only", true);
            photo.put("thumb_b64", "/9j/thumb" + i);
            photos.add(photo);
        }
        root.set("photos", photos);

        ExecutionResult out = callbackShell().textOrVision(root);

        assertEquals(2, out.images().size());
        assertTrue(out.jsonText().contains("\"candidate_only\":true"));
        assertTrue(out.jsonText().contains("\"requested_limit\":1"));
    }

    @Test
    void semanticThenDateSortDoesNotPromoteWeakMatchesJustBecauseTheyAreNewer() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("scanned", 2);
        var photos = mapper.createArrayNode();
        ObjectNode older = mapper.createObjectNode();
        older.put("id", "older");
        older.put("name", "older-match.jpg");
        older.put("local_score", 100);
        older.put("date_taken_ms", 1_000L);
        ObjectNode newer = mapper.createObjectNode();
        newer.put("id", "newer");
        newer.put("name", "newer-match.jpg");
        newer.put("local_score", 10);
        newer.put("date_taken_ms", 2_000L);
        photos.add(older);
        photos.add(newer);
        root.set("photos", photos);

        ObjectNode args = mapper.createObjectNode();
        args.put("ranking_mode", "semantic_then_sort");
        args.put("sort_by", "date_taken");
        args.put("sort_direction", "desc");
        args.put("candidate_k", 8);
        var callback = callbackShell();
        ObjectNode out = callback.fallbackResult(
                "latest matching photo", root, photos,
                callback.searchContract(args, "latest matching photo", 1, 2),
                "test");

        assertEquals("older", out.path("photos").get(0).path("id").asText());
        assertEquals("semantic_then_sort", out.path("ranking").path("mode").asText());
        assertEquals("date_taken", out.path("sort").path("by").asText());
        assertEquals("show_primary", out.path("display_policy").asText());
    }

    @Test
    void semanticThenDateSortUsesDateWithinQualifiedMatches() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("scanned", 2);
        var photos = mapper.createArrayNode();
        ObjectNode older = mapper.createObjectNode();
        older.put("id", "older");
        older.put("name", "older-match.jpg");
        older.put("local_score", 100);
        older.put("date_taken_ms", 1_000L);
        ObjectNode newer = mapper.createObjectNode();
        newer.put("id", "newer");
        newer.put("name", "newer-match.jpg");
        newer.put("local_score", 98);
        newer.put("date_taken_ms", 2_000L);
        photos.add(older);
        photos.add(newer);
        root.set("photos", photos);

        ObjectNode args = mapper.createObjectNode();
        args.put("ranking_mode", "semantic_then_sort");
        args.put("sort_by", "date_taken");
        args.put("sort_direction", "desc");
        args.put("candidate_k", 8);
        var callback = callbackShell();
        ObjectNode out = callback.fallbackResult(
                "latest matching photo", root, photos,
                callback.searchContract(args, "latest matching photo", 1, 2),
                "test");

        assertEquals("newer", out.path("photos").get(0).path("id").asText());
    }

    @Test
    void singleResultKeepsOnlySelectedPhotoAndHidesReviewCandidatesByDefault() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("scanned", 3);
        var photos = mapper.createArrayNode();
        for (int i = 0; i < 3; i++) {
            ObjectNode photo = mapper.createObjectNode();
            photo.put("id", "photo-" + i);
            photo.put("name", "photo-" + i + ".jpg");
            photo.put("local_score", 100 - i);
            photo.put("date_taken_ms", 1_000L + i);
            photo.put("thumb_b64", "/9j/thumb" + i);
            photos.add(photo);
        }
        root.set("photos", photos);

        ObjectNode args = mapper.createObjectNode();
        args.put("limit", 1);
        args.put("review_limit", 3);
        var callback = callbackShell();
        ObjectNode out = callback.fallbackResult(
                "cat photo", root, photos,
                callback.searchContract(args, "cat photo", 1, 3),
                "test");

        assertEquals("selected", out.path("result_type").asText());
        assertFalse(out.path("candidate_only").asBoolean());
        assertEquals("show_primary", out.path("display_policy").asText());
        assertEquals(1, out.path("photos").size());
        assertEquals("photo-0", out.path("photos").get(0).path("id").asText());
        assertTrue(out.path("photos").get(0).has("thumb_b64"));
        assertFalse(out.has("review_candidates"));
        assertEquals("photos.get_full", out.path("next").path("recommended_tool").asText());
        assertEquals("photo-0", out.path("next").path("args").path("id").asText());
    }

    @Test
    void explicitCandidateDebugExposesOnlyRedactedReviewCandidates() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("scanned", 3);
        var photos = mapper.createArrayNode();
        for (int i = 0; i < 3; i++) {
            ObjectNode photo = mapper.createObjectNode();
            photo.put("id", "photo-" + i);
            photo.put("name", "photo-" + i + ".jpg");
            photo.put("local_score", 100 - i);
            photo.put("thumb_b64", "/9j/thumb" + i);
            photos.add(photo);
        }
        root.set("photos", photos);

        ObjectNode args = mapper.createObjectNode();
        args.put("limit", 1);
        args.put("review_limit", 3);
        args.put("display", "show_candidates");
        var callback = callbackShell();
        ObjectNode out = callback.fallbackResult(
                "cat photo", root, photos,
                callback.searchContract(args, "cat photo", 1, 3),
                "test");

        assertEquals(1, out.path("photos").size());
        assertEquals(2, out.path("review_candidates").size());
        assertFalse(out.path("review_candidates").get(0).has("thumb_b64"));
    }

    @Test
    void indexedSearchUsesFinalLimitAndNameFilterForChatServiceQuery() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        AtomicReference<PhotoAssetSearchRequest> captured = new AtomicReference<>();
        PhotoEmbeddingService photoEmbeddingService = mock(PhotoEmbeddingService.class);
        when(photoEmbeddingService.embedText("latest cat")).thenReturn(new float[]{0.1f, 0.2f});
        when(photoEmbeddingService.model()).thenReturn("test-clip");
        when(photoEmbeddingService.dim()).thenReturn(2);
        InternalChatFeignClient chat = mock(InternalChatFeignClient.class);
        when(chat.searchPhotos(any())).thenAnswer(invocation -> {
            PhotoAssetSearchRequest req = invocation.getArgument(0);
            captured.set(req);
            return List.of(new PhotoAssetSearchResult(
                    UUID.randomUUID(),
                    deviceId,
                    "media-1",
                    "Screenshot_mm_cat.jpg",
                    null,
                    null,
                    1_000L,
                    null,
                    null,
                    100,
                    100,
                    "image/jpeg",
                    null,
                    "/9j/thumb",
                    "test-clip",
                    2,
                    0.1d,
                    0.9d));
        });
        SemanticPhotoSearchCallback callback = indexedCallback(photoEmbeddingService, chat);

        ObjectNode args = mapper.createObjectNode();
        args.put("query", "latest cat");
        args.put("limit", 1);
        args.put("candidate_k", 50);
        args.put("name_contains", "mm");
        args.put("ranking_mode", "semantic_then_sort");
        args.put("sort_by", "date_taken");
        args.put("sort_direction", "desc");

        ExecutionResult out = callback.executeJsonToolUse(args, userId, UUID.randomUUID(), null);
        PhotoAssetSearchRequest req = captured.get();

        assertEquals(1, req.resultLimit());
        assertEquals("mm", req.nameContains());
        assertEquals(50, req.topK());
        assertFalse(out.jsonText().contains("review_candidates"));
    }

    @Test
    void searchContractDefaultsReviewAndCandidateCountsToAgentChoices() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("scanned", 1);
        var photos = mapper.createArrayNode();
        ObjectNode photo = mapper.createObjectNode();
        photo.put("id", "photo-1");
        photo.put("name", "photo-1.jpg");
        photos.add(photo);
        root.set("photos", photos);
        ObjectNode args = mapper.createObjectNode();
        args.put("ranking_mode", "semantic_then_sort");
        args.put("sort_by", "date_taken");
        args.put("sort_direction", "desc");
        var callback = callbackShell();

        ObjectNode out = callback.fallbackResult(
                "latest cat", root, photos,
                callback.searchContract(args, "latest cat", 1, 1),
                "test");

        assertEquals(1, out.path("requested_limit").asInt());
        assertEquals(1, out.path("review_limit").asInt());
        assertEquals(1, out.path("candidate_k").asInt());
    }

    @Test
    void searchContractHonorsExplicitReviewAndCandidateCounts() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("scanned", 5);
        var photos = mapper.createArrayNode();
        for (int i = 0; i < 5; i++) {
            ObjectNode photo = mapper.createObjectNode();
            photo.put("id", "photo-" + i);
            photo.put("name", "photo-" + i + ".jpg");
            photos.add(photo);
        }
        root.set("photos", photos);
        ObjectNode args = mapper.createObjectNode();
        args.put("review_limit", 5);
        args.put("candidate_k", 9);
        args.put("ranking_mode", "semantic_then_sort");
        args.put("sort_by", "date_taken");
        args.put("sort_direction", "desc");
        var callback = callbackShell();

        ObjectNode out = callback.fallbackResult(
                "latest cat", root, photos,
                callback.searchContract(args, "latest cat", 2, 5),
                "test");

        assertEquals(2, out.path("requested_limit").asInt());
        assertEquals(5, out.path("review_limit").asInt());
        assertEquals(9, out.path("candidate_k").asInt());
    }

    @Test
    void realtimeFallbackRequestsAgentChosenCandidateKFromDevice() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        DeviceToolDispatcher dispatcher = mock(DeviceToolDispatcher.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embed(anyString())).thenThrow(new IllegalStateException("offline"));
        ObjectNode candidateRoot = mapper.createObjectNode();
        candidateRoot.put("scanned", 10);
        var photos = mapper.createArrayNode();
        ObjectNode photo = mapper.createObjectNode();
        photo.put("id", "photo-1");
        photo.put("name", "photo-1.jpg");
        photo.put("local_score", 1);
        photos.add(photo);
        candidateRoot.set("photos", photos);
        when(dispatcher.dispatch(eq(deviceId), eq(userId), eq("photos.semantic_candidates"), any()))
                .thenReturn(ToolResult.ok(candidateRoot));
        SemanticPhotoSearchCallback callback = realtimeCallback(deviceId, userId, dispatcher, embeddingService);
        ObjectNode args = mapper.createObjectNode();
        args.put("query", "cat");
        args.put("limit", 1);
        args.put("candidate_k", 7);

        callback.executeJsonToolUse(args, userId, UUID.randomUUID(), null);

        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(dispatcher).dispatch(eq(deviceId), eq(userId), eq("photos.semantic_candidates"), captor.capture());
        assertEquals(7, captor.getValue().path("limit").asInt());
    }

    @Test
    void semanticThenSortThresholdUsesRelativeAndAbsoluteFloor() {
        assertEquals(0.21568d,
                SemanticPhotoSearchCallback.semanticThenSortQualifyingScore(0.2696d, 0.1d),
                0.00001d);
        assertEquals(0.2d,
                SemanticPhotoSearchCallback.semanticThenSortQualifyingScore(0.22d, 0.2d),
                0.00001d);
    }

    @Test
    void copyIfPresentSkipsEmptyPhotoFilters() {
        ObjectNode src = mapper.createObjectNode();
        src.put("name_contains", "");
        src.put("date_after", 0);
        src.put("date_before", 0);
        src.put("bucket_id", "camera");

        ObjectNode dst = mapper.createObjectNode();
        SemanticPhotoSearchCallback.copyIfPresent(src, dst, "name_contains");
        SemanticPhotoSearchCallback.copyIfPresent(src, dst, "date_after");
        SemanticPhotoSearchCallback.copyIfPresent(src, dst, "date_before");
        SemanticPhotoSearchCallback.copyIfPresent(src, dst, "bucket_id");

        assertFalse(dst.has("name_contains"));
        assertFalse(dst.has("date_after"));
        assertFalse(dst.has("date_before"));
        assertEquals("camera", dst.path("bucket_id").asText());
    }

    private SemanticPhotoSearchCallback callbackShell() {
        return new SemanticPhotoSearchCallback(mapper, true);
    }

    private SemanticPhotoSearchCallback indexedCallback(PhotoEmbeddingService photoEmbeddingService,
                                                       InternalChatFeignClient chat) {
        AgentProperties.Photos photos = new AgentProperties.Photos(
                "test-clip",
                "http://127.0.0.1",
                "",
                2,
                null,
                null,
                "image",
                true,
                false,
                false,
                8,
                0.2,
                8,
                5);
        AgentProperties.Agent agent = new AgentProperties.Agent(
                0,
                null,
                4096,
                24,
                24,
                10,
                List.of(),
                null,
                photos);
        AgentProperties props = new AgentProperties(new AgentProperties.Jwt("secret", "issuer"), agent);
        return new SemanticPhotoSearchCallback(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                mapper,
                null,
                photoEmbeddingService,
                chat,
                null,
                true,
                props);
    }

    private SemanticPhotoSearchCallback realtimeCallback(UUID deviceId,
                                                        UUID userId,
                                                        DeviceToolDispatcher dispatcher,
                                                        EmbeddingService embeddingService) {
        AgentProperties.Photos photos = new AgentProperties.Photos(
                "test-clip",
                "http://127.0.0.1",
                "",
                2,
                null,
                null,
                "image",
                false,
                true,
                false,
                8,
                0.2,
                8,
                5);
        AgentProperties.Agent agent = new AgentProperties.Agent(
                0,
                null,
                4096,
                24,
                24,
                10,
                List.of(),
                null,
                photos);
        AgentProperties props = new AgentProperties(new AgentProperties.Jwt("secret", "issuer"), agent);
        return new SemanticPhotoSearchCallback(
                deviceId,
                userId,
                dispatcher,
                mapper,
                embeddingService,
                null,
                null,
                null,
                false,
                props);
    }
}
