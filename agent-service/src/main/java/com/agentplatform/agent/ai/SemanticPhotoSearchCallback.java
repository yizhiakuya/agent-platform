package com.agentplatform.agent.ai;

import com.agentplatform.agent.chat.SseEvent;
import com.agentplatform.agent.client.DeviceToolDispatcher;
import com.agentplatform.agent.client.InternalChatFeignClient;
import com.agentplatform.agent.config.AgentProperties;
import com.agentplatform.api.chat.PhotoAssetSearchRequest;
import com.agentplatform.api.chat.PhotoAssetSearchResult;
import com.agentplatform.protocol.ToolResult;
import com.agentplatform.protocol.ToolSpec;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-composed semantic photo search.
 *
 * <p>Primary path: query the server-side pgvector photo index populated by
 * Android thumbnail uploads and {@link PhotoIndexEmbeddingJob}. Fallback path:
 * if the index is empty or the multimodal embedding provider is unavailable,
 * use the older realtime Android candidate scan.
 */
public class SemanticPhotoSearchCallback extends RemoteToolCallback {

    public static final String TOOL_NAME = "photos.semantic_search";
    private static final String CANDIDATE_K_FIELD = "candidate_k";
    private static final String CANDIDATE_TOOL = "photos.semantic_candidates";
    private static final String DATE_TAKEN_MS_FIELD = "date_taken_ms";
    private static final String DISPLAY_POLICY_FIELD = "display_policy";
    private static final String FALLBACK_REALTIME_FIELD = "fallback_realtime";
    private static final String MATCH_REASON_FIELD = "match_reason";
    private static final String MATCH_SCORE_FIELD = "match_score";
    private static final String MAX_DIM_FIELD = "max_dim";
    private static final String PHOTOS_GET_FULL_TOOL = "photos.get_full";
    private static final String QUERY_FIELD = "query";
    private static final String REVIEW_CANDIDATES_FIELD = "review_candidates";
    private static final String REVIEW_LIMIT_FIELD = "review_limit";
    private static final String SEMANTIC_ENGINE_FIELD = "semantic_engine";
    private static final String SOURCE_FIELD = "source";
    private static final Logger log = LoggerFactory.getLogger(SemanticPhotoSearchCallback.class);

    private final UUID deviceId;
    private final UUID boundUserId;
    private final DeviceToolDispatcher dispatcher;
    private final ObjectMapper mapper;
    private final EmbeddingService embeddingService;
    private final PhotoEmbeddingService photoEmbeddingService;
    private final InternalChatFeignClient internalChatClient;
    private final boolean visionEnabled;
    private final double indexMinScore;
    private final boolean photoIndexEnabled;
    private final boolean fallbackRealtime;

    public SemanticPhotoSearchCallback(UUID deviceId,
                                       UUID userId,
                                       DeviceToolDispatcher dispatcher,
                                       ObjectMapper mapper,
                                       EmbeddingService embeddingService,
                                       PhotoEmbeddingService photoEmbeddingService,
                                       InternalChatFeignClient internalChatClient,
                                       ApplicationEventPublisher events,
                                       boolean visionEnabled,
                                       AgentProperties props) {
        super(deviceId, userId, spec(mapper), dispatcher, mapper, List.of(), events, visionEnabled);
        this.deviceId = deviceId;
        this.boundUserId = userId;
        this.dispatcher = dispatcher;
        this.mapper = mapper;
        this.embeddingService = embeddingService;
        this.photoEmbeddingService = photoEmbeddingService;
        this.internalChatClient = internalChatClient;
        this.visionEnabled = visionEnabled;
        AgentProperties.Photos photos = props == null || props.agent() == null ? null : props.agent().photos();
        this.indexMinScore = photos == null ? 0.20d : photos.minScore();
        this.photoIndexEnabled = photos == null || Boolean.TRUE.equals(photos.enabled());
        this.fallbackRealtime = photos == null || Boolean.TRUE.equals(photos.fallbackRealtime());
    }

    SemanticPhotoSearchCallback(ObjectMapper mapper, boolean visionEnabled) {
        super(null, null, spec(mapper), null, mapper, List.of(), null, visionEnabled);
        this.deviceId = null;
        this.boundUserId = null;
        this.dispatcher = null;
        this.mapper = mapper;
        this.embeddingService = null;
        this.photoEmbeddingService = null;
        this.internalChatClient = null;
        this.visionEnabled = visionEnabled;
        this.indexMinScore = 0.20d;
        this.photoIndexEnabled = false;
        this.fallbackRealtime = true;
    }

    @Override
    public ExecutionResult executeToolUse(ToolUseBlock tu, UUID userId, UUID sessionId, ChatEventSink sink) {
        return executeJsonToolUse(parseJsonArgs(tu), userId, sessionId, sink);
    }

    @Override
    public ExecutionResult executeJsonToolUse(JsonNode args, UUID userId, UUID sessionId, ChatEventSink sink) {
        if (args == null || args.isNull()) {
            args = mapper.createObjectNode();
        }
        String query = args.path(QUERY_FIELD).asText("").trim();
        if (query.isBlank()) {
            return ExecutionResult.error("query is required");
        }
        int limit = clamp(args.path("limit").asInt(3), 1, 12);
        int reviewLimit = args.has(REVIEW_LIMIT_FIELD)
                ? clamp(args.path(REVIEW_LIMIT_FIELD).asInt(limit), limit, 12)
                : limit;
        SearchContract contract = searchContract(args, query, limit, reviewLimit);
        int scanLimit = clamp(
                args.path("scan_limit").asInt(Math.max(60, contract.candidateK() * 4)),
                contract.candidateK(),
                200);

        if (sink != null) {
            sink.emit(SseEvent.toolCallStarted(mapper, deviceId, TOOL_NAME, args));
        }

        ExecutionResult indexed = tryIndexedSearch(query, args, userId, contract, sink);
        if (indexed != null) {
            return indexed;
        }
        if (!fallbackRealtime) {
            ObjectNode empty = mapper.createObjectNode();
            empty.put(QUERY_FIELD, query);
            empty.put("count", 0);
            empty.put(SEMANTIC_ENGINE_FIELD, "photo_index_unavailable");
            empty.put(FALLBACK_REALTIME_FIELD, false);
            empty.set("photos", mapper.createArrayNode());
            if (sink != null) sink.emit(SseEvent.toolCallResult(mapper, TOOL_NAME, empty));
            return ExecutionResult.text(empty.toString());
        }

        ObjectNode candidateArgs = mapper.createObjectNode();
        int candidateLimit = Math.min(20, Math.min(scanLimit, Math.max(contract.candidateK(), contract.reviewLimit())));
        candidateArgs.put(QUERY_FIELD, query);
        candidateArgs.put("limit", candidateLimit);
        candidateArgs.put("scan_limit", scanLimit);
        candidateArgs.put("ocr", !args.has("ocr") || args.path("ocr").asBoolean(true));
        copyIfPresent(args, candidateArgs, "bucket_id");
        copyIfPresent(args, candidateArgs, "name_contains");
        copyIfPresent(args, candidateArgs, "date_after");
        copyIfPresent(args, candidateArgs, "date_before");

        ToolResult raw = dispatcher.dispatch(deviceId, boundUserId, CANDIDATE_TOOL, candidateArgs);
        if (raw == null || raw.hasError()) {
            String msg = raw == null || raw.error() == null ? "semantic candidate scan failed" : raw.error().message();
            if (sink != null) sink.emit(SseEvent.error(mapper, "Tool '" + TOOL_NAME + "' failed: " + msg));
            return ExecutionResult.error(msg);
        }

        JsonNode candidateRoot = raw.value();
        JsonNode photos = candidateRoot == null ? null : candidateRoot.path("photos");
        if (photos == null || !photos.isArray() || photos.isEmpty()) {
            ObjectNode empty = mapper.createObjectNode();
            empty.put(QUERY_FIELD, query);
            empty.put("count", 0);
            empty.put("scanned", candidateRoot == null ? 0 : candidateRoot.path("scanned").asInt(0));
            empty.put(SEMANTIC_ENGINE_FIELD, "realtime_text_embedding");
            empty.put(FALLBACK_REALTIME_FIELD, true);
            empty.set("photos", mapper.createArrayNode());
            if (sink != null) sink.emit(SseEvent.toolCallResult(mapper, TOOL_NAME, empty));
            return ExecutionResult.text(empty.toString());
        }

        float[] queryEmbedding;
        try {
            queryEmbedding = embeddingService.embed("query: " + query);
        } catch (Exception e) {
            log.warn("[semantic-photos] query embedding failed: {}", e.getMessage());
            ObjectNode fallback = fallbackResult(query, candidateRoot, photos, contract, "embedding_failed: " + e.getMessage());
            if (sink != null) sink.emit(SseEvent.toolCallResult(mapper, TOOL_NAME, fallback));
            return textOrVision(fallback);
        }

        Set<String> queryTerms = tokenize(query);
        List<ScoredPhoto> scored = new ArrayList<>();
        for (JsonNode photo : photos) {
            String semanticText = photo.path("semantic_text").asText("");
            if (semanticText.isBlank()) {
                semanticText = photo.path("name").asText("");
            }
            float semanticScore = 0.0f;
            String reason = "embedding";
            try {
                float[] photoEmbedding = embeddingService.embed("photo: " + semanticText);
                semanticScore = cosine(queryEmbedding, photoEmbedding);
            } catch (Exception e) {
                reason = "local_score_only";
                log.debug("[semantic-photos] candidate embedding failed id={} err={}",
                        photo.path("id").asText(), e.getMessage());
            }
            int localScore = photo.path("local_score").asInt(0);
            float recencyScore = recencyScore(photo.path(DATE_TAKEN_MS_FIELD).asLong(0L));
            float visualScore = visualLabelScore(queryTerms, photo.path("visual_labels"));
            float deviceVisualScore = (float) photo.path("visual_score").asDouble(0.0);
            float totalScore = scoreCandidate(semanticScore, localScore, visualScore, deviceVisualScore, recencyScore);
            String matchReason = reason;
            if (visualScore > 0.0f || deviceVisualScore > 0.0f) {
                matchReason += "+visual_labels";
            }
            scored.add(new ScoredPhoto(
                    photo,
                    totalScore,
                    semanticScore,
                    localScore,
                    visualScore,
                    deviceVisualScore,
                    recencyScore,
                    matchReason));
        }

        sortScoredPhotos(scored, contract);

        ArrayNode outPhotos = mapper.createArrayNode();
        ArrayNode reviewCandidates = mapper.createArrayNode();
        int take = Math.min(reviewLimit, scored.size());
        int finalTake = Math.min(limit, scored.size());
        for (int i = 0; i < take; i++) {
            ScoredPhoto hit = scored.get(i);
            ObjectNode copy = hit.photo().deepCopy();
            copy.put("rank", i + 1);
            copy.put("semantic_score", round(hit.semanticScore()));
            copy.put("server_visual_score", round(hit.visualScore()));
            copy.put("device_visual_score", round(hit.deviceVisualScore()));
            copy.put("recency_score", round(hit.recencyScore()));
            copy.put(MATCH_SCORE_FIELD, round(hit.totalScore()));
            copy.put(MATCH_REASON_FIELD, hit.reason());
            copy.put("candidate_only", i >= finalTake);
            if (i < finalTake) {
                copy.put(SOURCE_FIELD, "realtime_scan");
                outPhotos.add(copy);
            } else {
                reviewCandidates.add(withoutBinaryFields(copy));
            }
        }

        ObjectNode result = mapper.createObjectNode();
        result.put("ok", true);
        result.put("schema_version", "agent_tool_contract/v1");
        result.put("tool", TOOL_NAME);
        result.put("result_type", "selected");
        result.put(QUERY_FIELD, query);
        result.put("count", outPhotos.size());
        result.put("requested_limit", limit);
        result.put(REVIEW_LIMIT_FIELD, reviewLimit);
        result.put("reviewed_count", take);
        result.put(CANDIDATE_K_FIELD, contract.candidateK());
        result.put("candidate_only", false);
        result.put(DISPLAY_POLICY_FIELD, resultDisplayPolicy(contract));
        ObjectNode ranking = mapper.createObjectNode();
        ranking.put("mode", contract.rankingMode());
        ranking.put(CANDIDATE_K_FIELD, contract.candidateK());
        ranking.put("min_score", round((float) contract.minScore()));
        result.set("ranking", ranking);
        ObjectNode sort = mapper.createObjectNode();
        sort.put("by", contract.sortBy());
        sort.put("direction", contract.sortDirection());
        result.set("sort", sort);
        result.put("scanned", candidateRoot.path("scanned").asInt(scored.size()));
        result.put(SEMANTIC_ENGINE_FIELD, "realtime_text_embedding+visual_labels");
        result.put(FALLBACK_REALTIME_FIELD, true);
        result.put("embedding_dim", embeddingService.dim());
        result.set("photos", outPhotos);
        attachDisplayMedia(result, outPhotos);
        if (shouldExposeReviewCandidates(contract) && !reviewCandidates.isEmpty()) {
            result.set(REVIEW_CANDIDATES_FIELD, reviewCandidates);
        }
        addInspectNext(result);
        attachFullImageForSingleResult(result, contract);

        if (sink != null) {
            sink.emit(SseEvent.toolCallResult(mapper, TOOL_NAME, result));
        }
        return textOrVision(result);
    }

    private ExecutionResult tryIndexedSearch(String query,
                                             JsonNode args,
                                             UUID userId,
                                             SearchContract contract,
                                             ChatEventSink sink) {
        if (!photoIndexEnabled || photoEmbeddingService == null || internalChatClient == null || userId == null) {
            return null;
        }
        float[] queryEmbedding;
        try {
            queryEmbedding = photoEmbeddingService.embedText(query);
        } catch (Exception e) {
            log.warn("[semantic-photos] photo-index query embedding failed: {}", e.getMessage());
            return null;
        }
        try {
            int topK = contract.candidateK();
            int requestedRows = shouldExposeReviewCandidates(contract)
                    ? contract.reviewLimit()
                    : contract.resultLimit();
            List<PhotoAssetSearchResult> hits = internalChatClient.searchPhotos(new PhotoAssetSearchRequest(
                    userId,
                    queryEmbedding,
                    topK,
                    stringFilter(args, "bucket_id"),
                    stringFilter(args, "name_contains"),
                    longFilter(args, "date_after"),
                    longFilter(args, "date_before"),
                    contract.minScore(),
                    requestedRows,
                    contract.rankingMode(),
                    contract.sortBy(),
                    contract.sortDirection()));
            ObjectNode result = indexedResult(query, hits, contract);
            if (sink != null) {
                sink.emit(SseEvent.toolCallResult(mapper, TOOL_NAME, result));
            }
            return textOrVision(result);
        } catch (Exception e) {
            log.warn("[semantic-photos] photo-index search failed: {}", e.getMessage());
            return null;
        }
    }

    private ObjectNode indexedResult(String query,
                                     List<PhotoAssetSearchResult> hits,
                                     SearchContract contract) {
        ArrayNode outPhotos = mapper.createArrayNode();
        ArrayNode reviewCandidates = mapper.createArrayNode();
        int take = Math.min(contract.reviewLimit(), hits == null ? 0 : hits.size());
        int finalTake = Math.min(contract.resultLimit(), hits == null ? 0 : hits.size());
        for (int i = 0; i < take; i++) {
            PhotoAssetSearchResult hit = hits.get(i);
            ObjectNode photo = mapper.createObjectNode();
            photo.put("id", hit.mediaStoreId());
            photo.put("asset_id", hit.id().toString());
            photo.put("device_id", hit.deviceId().toString());
            put(photo, "name", hit.name());
            put(photo, "bucket_id", hit.bucketId());
            put(photo, "bucket_name", hit.bucketName());
            put(photo, DATE_TAKEN_MS_FIELD, hit.dateTakenMs());
            put(photo, "date_modified_sec", hit.dateModifiedSec());
            put(photo, "size_bytes", hit.sizeBytes());
            put(photo, "width", hit.width());
            put(photo, "height", hit.height());
            put(photo, "mime_type", hit.mimeType());
            put(photo, "content_hash", hit.contentHash());
            put(photo, "thumb_b64", hit.thumbB64());
            put(photo, "embedding_model", hit.embeddingModel());
            put(photo, "embedding_dim", hit.embeddingDim());
            photo.put("rank", i + 1);
            photo.put("distance", round((float) hit.distance()));
            photo.put(MATCH_SCORE_FIELD, round((float) hit.score()));
            photo.put(MATCH_REASON_FIELD, "photo_index_embedding");
            photo.put("candidate_only", i >= finalTake);
            photo.put(SOURCE_FIELD, "photo_index");
            if (i < finalTake) {
                outPhotos.add(photo);
            } else {
                reviewCandidates.add(withoutBinaryFields(photo));
            }
        }

        ObjectNode result = mapper.createObjectNode();
        result.put("ok", true);
        result.put("schema_version", "agent_tool_contract/v1");
        result.put("tool", TOOL_NAME);
        result.put("result_type", "selected");
        result.put(QUERY_FIELD, query);
        result.put("count", outPhotos.size());
        result.put("requested_limit", contract.resultLimit());
        result.put(REVIEW_LIMIT_FIELD, contract.reviewLimit());
        result.put("reviewed_count", take);
        result.put(CANDIDATE_K_FIELD, contract.candidateK());
        result.put("candidate_only", false);
        result.put(DISPLAY_POLICY_FIELD, resultDisplayPolicy(contract));
        ObjectNode ranking = mapper.createObjectNode();
        ranking.put("mode", contract.rankingMode());
        ranking.put(CANDIDATE_K_FIELD, contract.candidateK());
        ranking.put("min_score", round((float) contract.minScore()));
        result.set("ranking", ranking);
        ObjectNode sort = mapper.createObjectNode();
        sort.put("by", contract.sortBy());
        sort.put("direction", contract.sortDirection());
        result.set("sort", sort);
        result.put(SEMANTIC_ENGINE_FIELD, "photo_index");
        result.put(FALLBACK_REALTIME_FIELD, false);
        result.put("embedding_model", photoEmbeddingService.model());
        result.put("embedding_dim", photoEmbeddingService.dim());
        result.put("min_score", round((float) contract.minScore()));
        result.set("photos", outPhotos);
        attachDisplayMedia(result, outPhotos);
        if (shouldExposeReviewCandidates(contract) && !reviewCandidates.isEmpty()) {
            result.set(REVIEW_CANDIDATES_FIELD, reviewCandidates);
        }
        addInspectNext(result);
        attachFullImageForSingleResult(result, contract);
        return result;
    }

    ExecutionResult textOrVision(ObjectNode result) {
        JsonNode modelResult = resultForLlm(result);
        if (visionEnabled) {
            List<PendingImage> images = new ArrayList<>();
            JsonNode stripped = stripB64ForLlmAndCollect(modelResult, "", images);
            return new ExecutionResult(stripped.toString(), images);
        }
        return ExecutionResult.text(stripB64ForLlm(modelResult).toString());
    }

    ObjectNode fallbackResult(String query, JsonNode candidateRoot, JsonNode photos, int limit, String reason) {
        SearchContract contract = new SearchContract(limit, limit, limit, indexMinScore,
                "semantic", "relevance", "desc", "hidden_candidates");
        return fallbackResult(query, candidateRoot, photos, contract, reason);
    }

    ObjectNode fallbackResult(String query, JsonNode candidateRoot, JsonNode photos, SearchContract contract, String reason) {
        List<JsonNode> ordered = new ArrayList<>();
        photos.forEach(ordered::add);
        Set<String> queryTerms = tokenize(query);
        sortFallbackPhotos(ordered, queryTerms, contract);
        ArrayNode arr = mapper.createArrayNode();
        ArrayNode reviewCandidates = mapper.createArrayNode();
        int take = Math.min(contract.reviewLimit(), ordered.size());
        int finalTake = Math.min(contract.resultLimit(), ordered.size());
        for (int i = 0; i < take; i++) {
            ObjectNode copy = ordered.get(i).deepCopy();
            float visualScore = visualLabelScore(queryTerms, copy.path("visual_labels"));
            copy.put("rank", i + 1);
            copy.put(MATCH_REASON_FIELD, reason);
            copy.put("server_visual_score", round(visualScore));
            copy.put(MATCH_SCORE_FIELD, round((float) fallbackScore(queryTerms, copy)));
            copy.put("candidate_only", i >= finalTake);
            if (i < finalTake) {
                copy.put(SOURCE_FIELD, "realtime_fallback");
                arr.add(copy);
            } else {
                reviewCandidates.add(withoutBinaryFields(copy));
            }
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("ok", true);
        out.put("schema_version", "agent_tool_contract/v1");
        out.put("tool", TOOL_NAME);
        out.put("result_type", "selected");
        out.put(QUERY_FIELD, query);
        out.put("count", arr.size());
        out.put("requested_limit", contract.resultLimit());
        out.put(REVIEW_LIMIT_FIELD, contract.reviewLimit());
        out.put("reviewed_count", take);
        out.put(CANDIDATE_K_FIELD, contract.candidateK());
        out.put("candidate_only", false);
        out.put(DISPLAY_POLICY_FIELD, resultDisplayPolicy(contract));
        ObjectNode ranking = mapper.createObjectNode();
        ranking.put("mode", contract.rankingMode());
        ranking.put(CANDIDATE_K_FIELD, contract.candidateK());
        ranking.put("min_score", round((float) contract.minScore()));
        out.set("ranking", ranking);
        ObjectNode sort = mapper.createObjectNode();
        sort.put("by", contract.sortBy());
        sort.put("direction", contract.sortDirection());
        out.set("sort", sort);
        out.put("scanned", candidateRoot.path("scanned").asInt(ordered.size()));
        out.put(SEMANTIC_ENGINE_FIELD, "local_text_visual_fallback");
        out.set("photos", arr);
        attachDisplayMedia(out, arr);
        if (shouldExposeReviewCandidates(contract) && !reviewCandidates.isEmpty()) {
            out.set(REVIEW_CANDIDATES_FIELD, reviewCandidates);
        }
        addInspectNext(out);
        attachFullImageForSingleResult(out, contract);
        return out;
    }

    private JsonNode resultForLlm(ObjectNode result) {
        ObjectNode copy = result.deepCopy();
        copy.remove("display_media");
        return copy;
    }

    private void attachDisplayMedia(ObjectNode result, JsonNode photos) {
        ArrayNode media = mapper.createArrayNode();
        if (photos != null && photos.isArray()) {
            for (JsonNode photo : photos) {
                ObjectNode item = displayMediaItem(photo);
                if (item != null) {
                    media.add(item);
                }
            }
        }
        result.set("display_media", media);

        ObjectNode display = mapper.createObjectNode();
        display.put("policy", result.path(DISPLAY_POLICY_FIELD).asText(resultDisplayPolicyFallback(media.size())));
        display.put("media_count", media.size());
        result.set("display", display);
    }

    private ObjectNode displayMediaItem(JsonNode photo) {
        if (photo == null || !photo.isObject()) {
            return null;
        }
        ObjectNode item = mapper.createObjectNode();
        String id = firstText(photo, "id", "media_store_id", "mediaStoreId");
        String mediaStoreId = firstText(photo, "media_store_id", "mediaStoreId", "id");
        String assetId = firstText(photo, "photo_asset_id", "asset_id", "assetId");

        item.put("kind", "image");
        put(item, "id", id);
        put(item, "media_store_id", mediaStoreId);
        put(item, "photo_asset_id", assetId);
        copyField(photo, item, "device_id");
        copyField(photo, item, "name");
        copyField(photo, item, "mime_type");
        copyField(photo, item, "width");
        copyField(photo, item, "height");
        copyField(photo, item, DATE_TAKEN_MS_FIELD);
        copyField(photo, item, "date_modified_sec");
        copyField(photo, item, "rank");
        copyField(photo, item, MATCH_SCORE_FIELD);
        copyField(photo, item, MATCH_REASON_FIELD);
        copyField(photo, item, SOURCE_FIELD);
        copyFirstText(photo, item, "preview_b64", "thumb_b64", "thumbnail_b64", "cover_thumb_b64");
        copyFirstText(photo, item, "preview_url", "thumb_url", "thumbnail_url", "cover_thumb_url");
        copyFirstText(photo, item, "image_url", "image_url", "asset_url", "url");

        if (id != null && !id.isBlank()) {
            item.put("open_tool", PHOTOS_GET_FULL_TOOL);
            ObjectNode args = mapper.createObjectNode();
            args.put("id", id);
            args.put(MAX_DIM_FIELD, 2048);
            item.set("open_args", args);
        }
        return item;
    }

    private static String resultDisplayPolicyFallback(int mediaCount) {
        return mediaCount == 1 ? "show_primary" : "show_grid";
    }

    private void attachFullImageForSingleResult(ObjectNode result, SearchContract contract) {
        if (dispatcher == null || deviceId == null || boundUserId == null || contract.resultLimit() != 1) {
            return;
        }
        JsonNode photos = result.path("photos");
        if (!photos.isArray() || photos.size() != 1) {
            return;
        }
        String id = photos.get(0).path("id").asText("").trim();
        if (id.isBlank()) {
            return;
        }
        ObjectNode args = mapper.createObjectNode();
        args.put("id", id);
        args.put(MAX_DIM_FIELD, 2048);
        try {
            ToolResult full = dispatcher.dispatch(deviceId, boundUserId, PHOTOS_GET_FULL_TOOL, args);
            ObjectNode fullNode = mapper.createObjectNode();
            fullNode.put("tool", PHOTOS_GET_FULL_TOOL);
            fullNode.set("args", args);
            if (full == null) {
                fullNode.put("ok", false);
                fullNode.put("error", "photos.get_full returned null");
            } else if (full.hasError()) {
                fullNode.put("ok", false);
                fullNode.put("error", full.error() == null ? "photos.get_full failed" : full.error().message());
            } else {
                fullNode.put("ok", true);
                fullNode.set("result", full.value() == null ? mapper.createObjectNode() : full.value());
                result.set("primary_image", fullNode);
                stripSelectedResultThumbnails(result);
            }
        } catch (Exception e) {
            log.warn("[semantic-photos] photos.get_full failed for selected id={}: {}", id, e.getMessage());
        }
    }

    private void addInspectNext(ObjectNode result) {
        JsonNode photos = result.path("photos");
        if (!photos.isArray() || photos.isEmpty()) {
            return;
        }
        ObjectNode args = mapper.createObjectNode();
        args.put("id", photos.get(0).path("id").asText(""));
        args.put(MAX_DIM_FIELD, 2048);
        ObjectNode next = mapper.createObjectNode();
        next.put("recommended_tool", PHOTOS_GET_FULL_TOOL);
        next.set("args", args);
        result.set("next", next);
    }

    private static String resultDisplayPolicy(SearchContract contract) {
        if ("collapsed_candidates".equals(contract.displayPolicy())) {
            return "collapsed_candidates";
        }
        return contract.resultLimit() == 1 ? "show_primary" : "show_grid";
    }

    private static boolean shouldExposeReviewCandidates(SearchContract contract) {
        return "collapsed_candidates".equals(contract.displayPolicy());
    }

    private ObjectNode withoutBinaryFields(ObjectNode src) {
        ObjectNode out = mapper.createObjectNode();
        src.fields().forEachRemaining(e -> {
            String key = e.getKey();
            if (!key.endsWith("_b64") && !key.toLowerCase(Locale.ROOT).contains("base64")) {
                out.set(key, e.getValue());
            }
        });
        return out;
    }

    private void stripSelectedResultThumbnails(ObjectNode result) {
        JsonNode photos = result.path("photos");
        if (!photos.isArray()) {
            return;
        }
        ArrayNode stripped = mapper.createArrayNode();
        for (JsonNode photo : photos) {
            if (photo instanceof ObjectNode obj) {
                stripped.add(withoutBinaryFields(obj));
            } else {
                stripped.add(photo);
            }
        }
        result.set("photos", stripped);
    }

    private JsonNode parseJsonArgs(ToolUseBlock tu) {
        try {
            Object raw = tu._input();
            if (raw == null) return mapper.createObjectNode();
            JsonNode node = mapper.valueToTree(raw);
            if (node != null && node.isTextual()) {
                try {
                    return mapper.readTree(node.asText());
                } catch (Exception ignore) {
                    return mapper.createObjectNode();
                }
            }
            return node == null || node.isNull() ? mapper.createObjectNode() : node;
        } catch (Exception e) {
            ObjectNode fallback = mapper.createObjectNode();
            fallback.put("_parse_error", e.getMessage());
            return fallback;
        }
    }

    static void copyIfPresent(JsonNode src, ObjectNode dst, String key) {
        JsonNode value = src.get(key);
        if (value != null && !value.isNull() && !isEmptyPhotoFilter(key, value)) {
            dst.set(key, value);
        }
    }

    private static boolean isEmptyPhotoFilter(String key, JsonNode value) {
        if (("bucket_id".equals(key) || "name_contains".equals(key))
                && value.isTextual() && value.asText().trim().isEmpty()) {
            return true;
        }
        return ("date_after".equals(key) || "date_before".equals(key))
                && value.isNumber() && value.asLong() <= 0L;
    }

    private static String stringFilter(JsonNode src, String key) {
        JsonNode value = src == null ? null : src.get(key);
        if (value == null || value.isNull()) return null;
        String out = value.asText("").trim();
        return out.isEmpty() ? null : out;
    }

    private static Long longFilter(JsonNode src, String key) {
        JsonNode value = src == null ? null : src.get(key);
        if (value == null || value.isNull() || !value.isNumber()) return null;
        long out = value.asLong();
        return out > 0L ? out : null;
    }

    SearchContract searchContract(JsonNode args, String query, int limit, int reviewLimit) {
        String rankingMode = stringFilter(args, "ranking_mode");
        String sortBy = stringFilter(args, "sort_by");
        String sortDirection = stringFilter(args, "sort_direction");
        if (rankingMode == null && args.has("ranking") && args.path("ranking").isObject()) {
            rankingMode = stringFilter(args.path("ranking"), "mode");
        }
        if (sortBy == null && args.has("sort") && args.path("sort").isObject()) {
            sortBy = stringFilter(args.path("sort"), "by");
        }
        if (sortDirection == null && args.has("sort") && args.path("sort").isObject()) {
            sortDirection = stringFilter(args.path("sort"), "direction");
        }

        boolean latestIntent = latestIntent(query);
        String normalizedSortBy = normalizeSortBy(sortBy);
        if ("relevance".equals(normalizedSortBy) && latestIntent) {
            normalizedSortBy = "date_taken";
        }
        String normalizedRanking = normalizeRankingMode(rankingMode);
        if (latestIntent && "date_taken".equals(normalizedSortBy) && "semantic".equals(normalizedRanking)) {
            normalizedRanking = "semantic_then_sort";
        }
        String normalizedDirection = normalizeSortDirection(sortDirection);
        int minCandidateK = Math.max(limit, reviewLimit);
        int candidateK = clamp(args.path(CANDIDATE_K_FIELD).asInt(minCandidateK), minCandidateK, 50);
        double minScore = args.path("min_score").isNumber()
                ? clampScore(args.path("min_score").asDouble())
                : indexMinScore;
        String display = stringFilter(args, "display");
        String displayPolicy = "show_candidates".equals(display)
                ? "collapsed_candidates"
                : "hidden_candidates";
        return new SearchContract(
                limit,
                reviewLimit,
                candidateK,
                minScore,
                normalizedRanking,
                normalizedSortBy,
                normalizedDirection,
                displayPolicy);
    }

    private static String normalizeRankingMode(String value) {
        String clean = cleanLower(value);
        if ("semantic_then_sort".equals(clean) || "sort_then_semantic".equals(clean)) {
            return clean;
        }
        return "semantic";
    }

    private static String normalizeSortBy(String value) {
        String clean = cleanLower(value);
        if ("date_taken".equals(clean)
                || "date_modified".equals(clean)
                || "created_at".equals(clean)
                || "updated_at".equals(clean)
                || "name".equals(clean)) {
            return clean;
        }
        return "relevance";
    }

    private static String normalizeSortDirection(String value) {
        return "asc".equals(cleanLower(value)) ? "asc" : "desc";
    }

    private static boolean latestIntent(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return q.contains("最新")
                || q.contains("最近")
                || q.contains("上一张")
                || q.contains("最后")
                || q.contains("latest")
                || q.contains("newest")
                || q.contains("most recent")
                || q.contains("recent");
    }

    private static double clampScore(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0d;
        return Math.max(0.0d, Math.min(0.99d, value));
    }

    private static String cleanLower(String value) {
        if (value == null) return null;
        String out = value.trim();
        return out.isEmpty() ? null : out.toLowerCase(Locale.ROOT);
    }

    private static void put(ObjectNode obj, String key, String value) {
        if (value != null) obj.put(key, value);
    }

    private static void put(ObjectNode obj, String key, Long value) {
        if (value != null) obj.put(key, value);
    }

    private static void put(ObjectNode obj, String key, Integer value) {
        if (value != null) obj.put(key, value);
    }

    private static String firstText(JsonNode src, String... keys) {
        if (src == null) return null;
        for (String key : keys) {
            JsonNode value = src.get(key);
            if (value != null && !value.isNull()) {
                String text = value.asText("").trim();
                if (!text.isEmpty()) return text;
            }
        }
        return null;
    }

    private static void copyField(JsonNode src, ObjectNode dst, String key) {
        JsonNode value = src.get(key);
        if (value != null && !value.isNull()) {
            dst.set(key, value);
        }
    }

    private static void copyFirstText(JsonNode src, ObjectNode dst, String dstKey, String... srcKeys) {
        String value = firstText(src, srcKeys);
        if (value != null) {
            dst.put(dstKey, value);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return 0.0f;
        double dot = 0.0;
        double aa = 0.0;
        double bb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            aa += a[i] * a[i];
            bb += b[i] * b[i];
        }
        if (aa == 0.0 || bb == 0.0) return 0.0f;
        return (float) (dot / (Math.sqrt(aa) * Math.sqrt(bb)));
    }

    private static float recencyScore(long dateMs) {
        if (dateMs <= 0L) return 0.0f;
        long ageMs = Math.max(0L, System.currentTimeMillis() - dateMs);
        double ageDays = ageMs / 86_400_000.0;
        return (float) (1.0 / (1.0 + Math.min(ageDays, 365.0)));
    }

    private static float scoreCandidate(float semanticScore,
                                        int localScore,
                                        float visualScore,
                                        float deviceVisualScore,
                                        float recencyScore) {
        return semanticScore
                + (localScore * 0.025f)
                + (visualScore * 0.35f)
                + (deviceVisualScore * 0.04f)
                + (recencyScore * 0.01f);
    }

    private static double fallbackScore(Set<String> queryTerms, JsonNode photo) {
        int localScore = photo.path("local_score").asInt(0);
        float visualScore = visualLabelScore(queryTerms, photo.path("visual_labels"));
        float deviceVisualScore = (float) photo.path("visual_score").asDouble(0.0);
        float recencyScore = recencyScore(photo.path(DATE_TAKEN_MS_FIELD).asLong(0L));
        return (localScore * 0.025d)
                + (visualScore * 0.35d)
                + (deviceVisualScore * 0.04d)
                + (recencyScore * 0.01d);
    }

    private static void sortScoredPhotos(List<ScoredPhoto> scored, SearchContract contract) {
        Comparator<ScoredPhoto> relevance = Comparator
                .comparing(ScoredPhoto::totalScore)
                .reversed()
                .thenComparing((ScoredPhoto p) -> p.photo().path(DATE_TAKEN_MS_FIELD).asLong(0L), Comparator.reverseOrder());
        if (!"semantic_then_sort".equals(contract.rankingMode()) || "relevance".equals(contract.sortBy())) {
            scored.sort(relevance);
            return;
        }
        Comparator<ScoredPhoto> metadata = scoredPhotoMetadataComparator(contract);
        double bestScore = scored.stream()
                .mapToDouble(ScoredPhoto::totalScore)
                .max()
                .orElse(0.0d);
        double threshold = semanticThenSortQualifyingScore(bestScore, contract.minScore());
        scored.sort(Comparator
                .comparing((ScoredPhoto p) -> p.totalScore() >= threshold)
                .reversed()
                .thenComparing(metadata)
                .thenComparing(relevance));
    }

    private static Comparator<ScoredPhoto> scoredPhotoMetadataComparator(SearchContract contract) {
        Comparator<ScoredPhoto> cmp = switch (contract.sortBy()) {
            case "date_modified" -> Comparator.comparing(p -> p.photo().path("date_modified_sec").asLong(0L));
            case "name" -> Comparator.comparing(p -> p.photo().path("name").asText(""));
            default -> Comparator.comparing(p -> p.photo().path(DATE_TAKEN_MS_FIELD).asLong(0L));
        };
        return "asc".equals(contract.sortDirection()) ? cmp : cmp.reversed();
    }

    private static void sortFallbackPhotos(List<JsonNode> photos, Set<String> queryTerms, SearchContract contract) {
        Comparator<JsonNode> relevance = Comparator
                .comparingDouble((JsonNode n) -> fallbackScore(queryTerms, n))
                .reversed()
                .thenComparing(Comparator.comparingLong((JsonNode n) -> n.path(DATE_TAKEN_MS_FIELD).asLong(0L)).reversed());
        if (!"semantic_then_sort".equals(contract.rankingMode()) || "relevance".equals(contract.sortBy())) {
            photos.sort(relevance);
            return;
        }
        Comparator<JsonNode> metadata = fallbackMetadataComparator(contract);
        double bestScore = photos.stream()
                .mapToDouble(n -> fallbackScore(queryTerms, n))
                .max()
                .orElse(0.0d);
        double threshold = semanticThenSortQualifyingScore(bestScore, contract.minScore());
        photos.sort(Comparator
                .comparing((JsonNode n) -> fallbackScore(queryTerms, n) >= threshold)
                .reversed()
                .thenComparing(metadata)
                .thenComparing(relevance));
    }

    private static Comparator<JsonNode> fallbackMetadataComparator(SearchContract contract) {
        Comparator<JsonNode> cmp = switch (contract.sortBy()) {
            case "date_modified" -> Comparator.comparingLong(n -> n.path("date_modified_sec").asLong(0L));
            case "name" -> Comparator.comparing(n -> n.path("name").asText(""));
            default -> Comparator.comparingLong(n -> n.path(DATE_TAKEN_MS_FIELD).asLong(0L));
        };
        return "asc".equals(contract.sortDirection()) ? cmp : cmp.reversed();
    }

    static double semanticThenSortQualifyingScore(double bestScore, double minScore) {
        double floor = Double.isFinite(minScore) ? Math.max(0.0d, Math.min(0.99d, minScore)) : 0.0d;
        if (!Double.isFinite(bestScore)) {
            return floor;
        }
        return Math.max(floor, Math.max(bestScore - 0.06d, bestScore * 0.80d));
    }

    static float visualLabelScore(Set<String> queryTerms, JsonNode labels) {
        if (queryTerms == null || queryTerms.isEmpty() || labels == null || !labels.isArray()) return 0.0f;
        Set<String> labelTerms = new LinkedHashSet<>();
        for (JsonNode label : labels) {
            labelTerms.addAll(expandVisualLabelTerms(label.asText("")));
        }
        if (labelTerms.isEmpty()) return 0.0f;
        float score = 0.0f;
        for (String term : queryTerms) {
            for (String labelTerm : labelTerms) {
                if (tokenMatches(term, labelTerm)) {
                    score += termWeight(term);
                    break;
                }
            }
        }
        return Math.min(score, 12.0f);
    }

    static Set<String> tokenize(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return terms;
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\u4e00-\\u9fff]+", " ");
        for (String part : normalized.split("\\s+")) {
            addTerm(terms, part);
        }
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch >= '\u4e00' && ch <= '\u9fff') {
                cjk.append(ch);
                addTerm(terms, String.valueOf(ch));
            }
        }
        for (int i = 0; i + 1 < cjk.length(); i++) {
            addTerm(terms, cjk.substring(i, i + 2));
        }
        return terms;
    }

    private static Set<String> expandVisualLabelTerms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return terms;
        String label = text.trim().toLowerCase(Locale.ROOT);
        terms.addAll(tokenize(label));
        for (String alias : VISUAL_LABEL_ALIASES.getOrDefault(label, List.of())) {
            terms.addAll(tokenize(alias));
        }
        return terms;
    }

    private static void addTerm(Set<String> terms, String term) {
        if (term == null || term.isBlank() || STOP_TERMS.contains(term)) return;
        if (term.length() >= 2 || isSingleCjk(term)) {
            terms.add(term);
        }
    }

    private static boolean isSingleCjk(String term) {
        return term.length() == 1 && term.charAt(0) >= '\u4e00' && term.charAt(0) <= '\u9fff';
    }

    private static boolean tokenMatches(String queryTerm, String labelTerm) {
        if (queryTerm.equals(labelTerm)) return true;
        if (queryTerm.length() < 2 || labelTerm.length() < 2) return false;
        return queryTerm.contains(labelTerm) || labelTerm.contains(queryTerm);
    }

    private static int termWeight(String term) {
        if (term.length() >= 4) return 3;
        return 2;
    }

    private static double round(float value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private static ToolSpec spec(ObjectMapper mapper) {
        try {
            return new ToolSpec(TOOL_NAME, DESCRIPTION, mapper.readTree(SCHEMA), false);
        } catch (Exception e) {
            throw new IllegalStateException("bad semantic photo search schema", e);
        }
    }

    private static final String DESCRIPTION = """
            Semantic photo search. Use when the user describes image contents,
            screenshot text, receipts, QR/payment codes, chat records, menus,
            documents, or "the picture where ...". This is a search tool:
            the photos array contains only the final selected result count,
            while internal recall candidates stay hidden unless explicitly
            requested for debugging. Choose limit and candidate_k explicitly
            for the task; choose review_limit only when debugging or browsing
            extra candidates. Use ranking_mode=semantic_then_sort and
            sort_by=date_taken/sort_direction=desc for "latest/recent X".
            Use limit=1 when the user asks for one image; use 3-5 for
            ambiguous searches; only use larger limits when the user asks to
            list several images.
            """;

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "Natural language description of what to find, including visible text if known."
                },
                "limit": {
                  "type": "integer",
                  "minimum": 1,
                  "maximum": 12,
                  "default": 3,
                  "description": "Final user-visible matches the agent wants. Use 1 for a single/latest/recent image request; use 3-5 for ambiguous searches; use higher values only when the user asks for several. The photos array must not exceed this count."
                },
                "review_limit": {
                  "type": "integer",
                  "minimum": 1,
                  "maximum": 12,
                  "description": "Internal audit/debug candidate count chosen by the agent. Normal confirmed_only results do not expose these candidates, and review candidates must not include image binaries."
                },
                "candidate_k": {
                  "type": "integer",
                  "minimum": 1,
                  "maximum": 50,
                  "description": "Internal semantic recall size before final sorting. The agent should choose this explicitly for the task, especially when metadata sorting needs broader recall."
                },
                "min_score": {
                  "type": "number",
                  "minimum": 0,
                  "maximum": 0.99,
                  "description": "Minimum semantic similarity score for indexed search candidates."
                },
                "ranking_mode": {
                  "type": "string",
                  "enum": ["semantic", "semantic_then_sort", "sort_then_semantic"],
                  "default": "semantic",
                  "description": "semantic sorts by vector relevance. semantic_then_sort recalls semantic candidates then sorts qualifying hits by metadata. sort_then_semantic recalls by metadata order then reranks by relevance."
                },
                "sort_by": {
                  "type": "string",
                  "enum": ["relevance", "date_taken", "date_modified", "created_at", "updated_at", "name"],
                  "default": "relevance",
                  "description": "Final sort field. For latest/recent photo requests use date_taken."
                },
                "sort_direction": {
                  "type": "string",
                  "enum": ["asc", "desc"],
                  "default": "desc",
                  "description": "Final sort direction. For latest/recent use desc."
                },
                "display": {
                  "type": "string",
                  "enum": ["confirmed_only", "show_candidates"],
                  "default": "confirmed_only",
                  "description": "confirmed_only hides internal candidates in normal UI and expects a follow-up inspect tool. show_candidates is for explicit candidate browsing/debugging."
                },
                "scan_limit": {
                  "type": "integer",
                  "minimum": 1,
                  "maximum": 200,
                  "default": 60,
                  "description": "Max recent/gallery photos to OCR and rank before returning top matches."
                },
                "bucket_id": {
                  "type": "string",
                  "description": "Optional album bucket id, usually from photos.list_albums."
                },
                "name_contains": {
                  "type": "string",
                  "description": "Optional filename prefilter, useful for screenshots from a known app."
                },
                "date_after": {
                  "type": "integer",
                  "description": "Only scan photos taken on/after this UNIX millisecond timestamp (UTC)."
                },
                "date_before": {
                  "type": "integer",
                  "description": "Only scan photos taken on/before this UNIX millisecond timestamp (UTC)."
                },
                "ocr": {
                  "type": "boolean",
                  "default": true,
                  "description": "Run on-device OCR before semantic ranking."
                }
              },
              "required": ["query", "limit", "candidate_k"]
            }
            """;

    private static final Map<String, List<String>> VISUAL_LABEL_ALIASES = Map.ofEntries(
            Map.entry("cat", List.of("猫", "猫咪", "小猫", "kitten", "kitty", "宠物", "动物")),
            Map.entry("dog", List.of("狗", "狗狗", "小狗", "puppy", "宠物", "动物")),
            Map.entry("pet", List.of("宠物", "动物")),
            Map.entry("animal", List.of("动物", "宠物")),
            Map.entry("bird", List.of("鸟", "动物")),
            Map.entry("flower", List.of("花", "植物")),
            Map.entry("plant", List.of("植物", "花草")),
            Map.entry("food", List.of("食物", "饭菜", "餐饮", "菜品")),
            Map.entry("meal", List.of("饭菜", "食物", "菜品")),
            Map.entry("person", List.of("人", "人物")),
            Map.entry("people", List.of("人", "人物")),
            Map.entry("selfie", List.of("自拍", "人像")),
            Map.entry("car", List.of("车", "汽车")),
            Map.entry("vehicle", List.of("车辆", "车")),
            Map.entry("screenshot", List.of("截图", "屏幕截图")),
            Map.entry("text", List.of("文字", "文本")),
            Map.entry("document", List.of("文档", "文件")),
            Map.entry("receipt", List.of("收据", "小票", "票据")),
            Map.entry("menu", List.of("菜单", "菜品")),
            Map.entry("qr code", List.of("二维码", "码")),
            Map.entry("computer", List.of("电脑", "计算机")),
            Map.entry("laptop", List.of("笔记本", "电脑")),
            Map.entry("mobile phone", List.of("手机", "电话")),
            Map.entry("screen", List.of("屏幕", "显示器")),
            Map.entry("keyboard", List.of("键盘")),
            Map.entry("book", List.of("书", "书本")),
            Map.entry("building", List.of("建筑", "楼", "房子")),
            Map.entry("house", List.of("房子", "住宅")),
            Map.entry("city", List.of("城市", "街景")),
            Map.entry("toy", List.of("玩具")),
            Map.entry("clothing", List.of("衣服", "服装")),
            Map.entry("fashion accessory", List.of("配饰", "饰品")),
            Map.entry("tableware", List.of("餐具")),
            Map.entry("drink", List.of("饮料", "喝的")),
            Map.entry("sky", List.of("天空")),
            Map.entry("water", List.of("水", "海", "湖")),
            Map.entry("beach", List.of("海边", "沙滩")),
            Map.entry("mountain", List.of("山", "山景"))
    );

    private static final Set<String> STOP_TERMS = Set.of(
            "的", "了", "在", "是", "和", "或", "找", "看", "这", "那", "张", "个", "一",
            "图", "片", "照", "图片", "照片", "相片"
    );

    private record ScoredPhoto(JsonNode photo,
                               float totalScore,
                               float semanticScore,
                               int localScore,
                               float visualScore,
                               float deviceVisualScore,
                               float recencyScore,
                               String reason) {}

    private record SearchContract(int resultLimit,
                                  int reviewLimit,
                                  int candidateK,
                                  double minScore,
                                  String rankingMode,
                                  String sortBy,
                                  String sortDirection,
                                  String displayPolicy) {}
}
