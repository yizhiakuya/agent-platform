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
    private static final String AGENT_TOOL_CONTRACT_VERSION = "agent_tool_contract/v1";
    private static final String BUCKET_ID_FIELD = "bucket_id";
    private static final String CANDIDATE_ONLY_FIELD = "candidate_only";
    private static final String CANDIDATE_K_FIELD = "candidate_k";
    private static final String CANDIDATE_TOOL = "photos.semantic_candidates";
    private static final String COLLAPSED_CANDIDATES = "collapsed_candidates";
    private static final String COUNT_FIELD = "count";
    private static final String DATE_AFTER_FIELD = "date_after";
    private static final String DATE_BEFORE_FIELD = "date_before";
    private static final String DATE_MODIFIED = "date_modified";
    private static final String DATE_MODIFIED_SEC_FIELD = "date_modified_sec";
    private static final String DATE_TAKEN = "date_taken";
    private static final String DATE_TAKEN_MS_FIELD = "date_taken_ms";
    private static final String DISPLAY_POLICY_FIELD = "display_policy";
    private static final String DIRECTION_FIELD = "direction";
    private static final String EMBEDDING_DIM_FIELD = "embedding_dim";
    private static final String FALLBACK_REALTIME_FIELD = "fallback_realtime";
    private static final String MATCH_REASON_FIELD = "match_reason";
    private static final String MATCH_SCORE_FIELD = "match_score";
    private static final String MAX_DIM_FIELD = "max_dim";
    private static final String MEDIA_STORE_ID_FIELD = "media_store_id";
    private static final String MIN_SCORE_FIELD = "min_score";
    private static final String NAME_CONTAINS_FIELD = "name_contains";
    private static final String PHOTOS_GET_FULL_TOOL = "photos.get_full";
    private static final String PHOTOS_FIELD = "photos";
    private static final String QUERY_FIELD = "query";
    private static final String RANKING_FIELD = "ranking";
    private static final String RELEVANCE = "relevance";
    private static final String REQUESTED_LIMIT_FIELD = "requested_limit";
    private static final String REVIEW_CANDIDATES_FIELD = "review_candidates";
    private static final String REVIEWED_COUNT_FIELD = "reviewed_count";
    private static final String REVIEW_LIMIT_FIELD = "review_limit";
    private static final String RESULT_TYPE_FIELD = "result_type";
    private static final String SCANNED_FIELD = "scanned";
    private static final String SCHEMA_VERSION_FIELD = "schema_version";
    private static final String SELECTED_RESULT_TYPE = "selected";
    private static final String SEMANTIC = "semantic";
    private static final String SEMANTIC_ENGINE_FIELD = "semantic_engine";
    private static final String SEMANTIC_THEN_SORT = "semantic_then_sort";
    private static final String SOURCE_FIELD = "source";
    private static final String VISUAL_LABELS_FIELD = "visual_labels";
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

    public SemanticPhotoSearchCallback(SemanticSearchContext context) {
        super(remoteToolContext(context));
        this.deviceId = context.deviceId();
        this.boundUserId = context.userId();
        this.dispatcher = context.dispatcher();
        this.mapper = context.mapper();
        this.embeddingService = context.embeddingService();
        this.photoEmbeddingService = context.photoEmbeddingService();
        this.internalChatClient = context.internalChatClient();
        this.visionEnabled = context.visionEnabled();
        AgentProperties.Photos photos = context.props() == null || context.props().agent() == null
                ? null
                : context.props().agent().photos();
        this.indexMinScore = photos == null ? 0.20d : photos.minScore();
        this.photoIndexEnabled = photos == null || Boolean.TRUE.equals(photos.enabled());
        this.fallbackRealtime = photos == null || Boolean.TRUE.equals(photos.fallbackRealtime());
    }

    SemanticPhotoSearchCallback(ObjectMapper mapper, boolean visionEnabled) {
        super(remoteToolContext(new SemanticSearchContext()
                .withMapper(mapper)
                .withVisionEnabled(visionEnabled)));
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

    private static RemoteToolContext remoteToolContext(SemanticSearchContext context) {
        return new RemoteToolContext()
                .withDeviceId(context.deviceId())
                .withUserId(context.userId())
                .withSpec(spec(context.mapper()))
                .withDispatcher(context.dispatcher())
                .withMapper(context.mapper())
                .withPreInterceptors(List.of())
                .withEvents(context.events())
                .withVisionEnabled(context.visionEnabled());
    }

    public static final class SemanticSearchContext {
        private UUID deviceId;
        private UUID userId;
        private DeviceToolDispatcher dispatcher;
        private ObjectMapper mapper;
        private EmbeddingService embeddingService;
        private PhotoEmbeddingService photoEmbeddingService;
        private InternalChatFeignClient internalChatClient;
        private ApplicationEventPublisher events;
        private boolean visionEnabled;
        private AgentProperties props;

        public SemanticSearchContext withDeviceId(UUID deviceId) {
            this.deviceId = deviceId;
            return this;
        }

        public SemanticSearchContext withUserId(UUID userId) {
            this.userId = userId;
            return this;
        }

        public SemanticSearchContext withDispatcher(DeviceToolDispatcher dispatcher) {
            this.dispatcher = dispatcher;
            return this;
        }

        public SemanticSearchContext withMapper(ObjectMapper mapper) {
            this.mapper = mapper;
            return this;
        }

        public SemanticSearchContext withEmbeddingService(EmbeddingService embeddingService) {
            this.embeddingService = embeddingService;
            return this;
        }

        public SemanticSearchContext withPhotoEmbeddingService(PhotoEmbeddingService photoEmbeddingService) {
            this.photoEmbeddingService = photoEmbeddingService;
            return this;
        }

        public SemanticSearchContext withInternalChatClient(InternalChatFeignClient internalChatClient) {
            this.internalChatClient = internalChatClient;
            return this;
        }

        public SemanticSearchContext withEvents(ApplicationEventPublisher events) {
            this.events = events;
            return this;
        }

        public SemanticSearchContext withVisionEnabled(boolean visionEnabled) {
            this.visionEnabled = visionEnabled;
            return this;
        }

        public SemanticSearchContext withProps(AgentProperties props) {
            this.props = props;
            return this;
        }

        private UUID deviceId() {
            return deviceId;
        }

        private UUID userId() {
            return userId;
        }

        private DeviceToolDispatcher dispatcher() {
            return dispatcher;
        }

        private ObjectMapper mapper() {
            return mapper;
        }

        private EmbeddingService embeddingService() {
            return embeddingService;
        }

        private PhotoEmbeddingService photoEmbeddingService() {
            return photoEmbeddingService;
        }

        private InternalChatFeignClient internalChatClient() {
            return internalChatClient;
        }

        private ApplicationEventPublisher events() {
            return events;
        }

        private boolean visionEnabled() {
            return visionEnabled;
        }

        private AgentProperties props() {
            return props;
        }
    }

    @Override
    public ExecutionResult executeToolUse(ToolUseBlock tu, UUID userId, UUID sessionId, ChatEventSink sink) {
        return executeJsonToolUse(parseJsonArgs(tu), userId, sessionId, sink);
    }

    @Override
    public ExecutionResult executeJsonToolUse(JsonNode args, UUID userId, UUID sessionId, ChatEventSink sink) {
        RealtimeSearchRequest request = realtimeSearchRequest(args);
        if (request.query().isBlank()) {
            return ExecutionResult.error("query is required");
        }
        emitToolCallStarted(request.args(), sink);
        ExecutionResult indexed = tryIndexedSearch(request.query(), request.args(), userId, request.contract(), sink);
        if (indexed != null) {
            return indexed;
        }
        return realtimeSearch(request, sink);
    }

    private RealtimeSearchRequest realtimeSearchRequest(JsonNode args) {
        JsonNode safeArgs = args == null || args.isNull() ? mapper.createObjectNode() : args;
        String query = safeArgs.path(QUERY_FIELD).asText("").trim();
        int limit = clamp(safeArgs.path("limit").asInt(3), 1, 12);
        int reviewLimit = safeArgs.has(REVIEW_LIMIT_FIELD)
                ? clamp(safeArgs.path(REVIEW_LIMIT_FIELD).asInt(limit), limit, 12)
                : limit;
        SearchContract contract = searchContract(safeArgs, query, limit, reviewLimit);
        int scanLimit = clamp(
                safeArgs.path("scan_limit").asInt(Math.max(60, contract.candidateK() * 4)),
                contract.candidateK(),
                200);
        return new RealtimeSearchRequest(safeArgs, query, limit, reviewLimit, scanLimit, contract);
    }

    private void emitToolCallStarted(JsonNode args, ChatEventSink sink) {
        if (sink != null) {
            sink.emit(SseEvent.toolCallStarted(mapper, deviceId, TOOL_NAME, args));
        }
    }

    private ExecutionResult realtimeSearch(RealtimeSearchRequest request, ChatEventSink sink) {
        if (!fallbackRealtime) {
            return emitTextResult(photoIndexUnavailableResult(request.query()), sink);
        }

        ToolResult raw = dispatcher.dispatch(deviceId, boundUserId, CANDIDATE_TOOL, candidateArgs(request));
        ExecutionResult failure = candidateScanFailure(raw, sink);
        if (failure != null) {
            return failure;
        }

        JsonNode candidateRoot = raw.value();
        JsonNode photos = candidatePhotos(candidateRoot);
        if (photos.isEmpty()) {
            return emitTextResult(emptyRealtimeResult(request.query(), candidateRoot), sink);
        }
        return embeddedRealtimeSearch(request, candidateRoot, photos, sink);
    }

    private ObjectNode photoIndexUnavailableResult(String query) {
        ObjectNode empty = mapper.createObjectNode();
        empty.put(QUERY_FIELD, query);
        empty.put(COUNT_FIELD, 0);
        empty.put(SEMANTIC_ENGINE_FIELD, "photo_index_unavailable");
        empty.put(FALLBACK_REALTIME_FIELD, false);
        empty.set(PHOTOS_FIELD, mapper.createArrayNode());
        return empty;
    }

    private ObjectNode candidateArgs(RealtimeSearchRequest request) {
        ObjectNode candidateArgs = mapper.createObjectNode();
        int candidateLimit = Math.min(20, Math.min(
                request.scanLimit(),
                Math.max(request.contract().candidateK(), request.contract().reviewLimit())));
        candidateArgs.put(QUERY_FIELD, request.query());
        candidateArgs.put("limit", candidateLimit);
        candidateArgs.put("scan_limit", request.scanLimit());
        candidateArgs.put("ocr", !request.args().has("ocr") || request.args().path("ocr").asBoolean(true));
        copyIfPresent(request.args(), candidateArgs, BUCKET_ID_FIELD);
        copyIfPresent(request.args(), candidateArgs, NAME_CONTAINS_FIELD);
        copyIfPresent(request.args(), candidateArgs, DATE_AFTER_FIELD);
        copyIfPresent(request.args(), candidateArgs, DATE_BEFORE_FIELD);
        return candidateArgs;
    }

    private ExecutionResult candidateScanFailure(ToolResult raw, ChatEventSink sink) {
        if (raw == null || raw.hasError()) {
            String msg = raw == null || raw.error() == null ? "semantic candidate scan failed" : raw.error().message();
            emitError(sink, "Tool '" + TOOL_NAME + "' failed: " + msg);
            return ExecutionResult.error(msg);
        }
        return null;
    }

    private void emitError(ChatEventSink sink, String message) {
        if (sink != null) {
            sink.emit(SseEvent.error(mapper, message));
        }
    }

    private JsonNode candidatePhotos(JsonNode candidateRoot) {
        JsonNode photos = candidateRoot == null ? null : candidateRoot.path(PHOTOS_FIELD);
        return photos != null && photos.isArray() ? photos : mapper.createArrayNode();
    }

    private ObjectNode emptyRealtimeResult(String query, JsonNode candidateRoot) {
        ObjectNode empty = mapper.createObjectNode();
        empty.put(QUERY_FIELD, query);
        empty.put(COUNT_FIELD, 0);
        empty.put(SCANNED_FIELD, candidateRoot == null ? 0 : candidateRoot.path(SCANNED_FIELD).asInt(0));
        empty.put(SEMANTIC_ENGINE_FIELD, "realtime_text_embedding");
        empty.put(FALLBACK_REALTIME_FIELD, true);
        empty.set(PHOTOS_FIELD, mapper.createArrayNode());
        return empty;
    }

    private ExecutionResult embeddedRealtimeSearch(RealtimeSearchRequest request,
                                                   JsonNode candidateRoot,
                                                   JsonNode photos,
                                                   ChatEventSink sink) {
        QueryEmbeddingResult queryEmbedding = queryEmbedding(request.query());
        if (queryEmbedding.failed()) {
            ObjectNode fallback = fallbackResult(
                    request.query(), candidateRoot, photos, request.contract(), queryEmbedding.failureReason());
            return emitVisionResult(fallback, sink);
        }
        List<ScoredPhoto> scored = scoreRealtimePhotos(photos, queryEmbedding.value(), tokenize(request.query()));
        sortScoredPhotos(scored, request.contract());
        return emitVisionResult(realtimeResult(request, candidateRoot, scored), sink);
    }

    private QueryEmbeddingResult queryEmbedding(String query) {
        try {
            return new QueryEmbeddingResult(embeddingService.embed("query: " + query), null);
        } catch (Exception e) {
            log.warn("[semantic-photos] query embedding failed: {}", e.getMessage());
            return new QueryEmbeddingResult(null, "embedding_failed: " + e.getMessage());
        }
    }

    private List<ScoredPhoto> scoreRealtimePhotos(JsonNode photos, float[] queryEmbedding, Set<String> queryTerms) {
        List<ScoredPhoto> scored = new ArrayList<>();
        for (JsonNode photo : photos) {
            scored.add(scoreRealtimePhoto(photo, queryEmbedding, queryTerms));
        }
        return scored;
    }

    private ScoredPhoto scoreRealtimePhoto(JsonNode photo, float[] queryEmbedding, Set<String> queryTerms) {
        CandidateEmbeddingResult embedding = candidateEmbedding(photo, queryEmbedding);
        int localScore = photo.path("local_score").asInt(0);
        float recencyScore = recencyScore(photo.path(DATE_TAKEN_MS_FIELD).asLong(0L));
        float visualScore = visualLabelScore(queryTerms, photo.path(VISUAL_LABELS_FIELD));
        float deviceVisualScore = (float) photo.path("visual_score").asDouble(0.0);
        float totalScore = scoreCandidate(embedding.score(), localScore, visualScore, deviceVisualScore, recencyScore);
        return new ScoredPhoto(
                photo,
                totalScore,
                embedding.score(),
                localScore,
                visualScore,
                deviceVisualScore,
                recencyScore,
                matchReason(embedding.reason(), visualScore, deviceVisualScore));
    }

    private CandidateEmbeddingResult candidateEmbedding(JsonNode photo, float[] queryEmbedding) {
        try {
            float[] photoEmbedding = embeddingService.embed("photo: " + semanticText(photo));
            return new CandidateEmbeddingResult(cosine(queryEmbedding, photoEmbedding), "embedding");
        } catch (Exception e) {
            log.debug("[semantic-photos] candidate embedding failed id={} err={}",
                    photo.path("id").asText(), e.getMessage());
            return new CandidateEmbeddingResult(0.0f, "local_score_only");
        }
    }

    private static String semanticText(JsonNode photo) {
        String semanticText = photo.path("semantic_text").asText("");
        return semanticText.isBlank() ? photo.path("name").asText("") : semanticText;
    }

    private static String matchReason(String reason, float visualScore, float deviceVisualScore) {
        return visualScore > 0.0f || deviceVisualScore > 0.0f ? reason + "+visual_labels" : reason;
    }

    private ObjectNode realtimeResult(RealtimeSearchRequest request, JsonNode candidateRoot, List<ScoredPhoto> scored) {
        ArrayNode outPhotos = mapper.createArrayNode();
        ArrayNode reviewCandidates = mapper.createArrayNode();
        int take = Math.min(request.reviewLimit(), scored.size());
        int finalTake = Math.min(request.limit(), scored.size());
        for (int i = 0; i < take; i++) {
            addRealtimePhoto(scored.get(i), i + 1, finalTake, outPhotos, reviewCandidates);
        }

        ObjectNode result = selectedResult(
                request.query(), outPhotos.size(), request.limit(), request.reviewLimit(), take, request.contract());
        result.put(SCANNED_FIELD, candidateRoot.path(SCANNED_FIELD).asInt(scored.size()));
        result.put(SEMANTIC_ENGINE_FIELD, "realtime_text_embedding+visual_labels");
        result.put(FALLBACK_REALTIME_FIELD, true);
        result.put(EMBEDDING_DIM_FIELD, embeddingService.dim());
        result.set(PHOTOS_FIELD, outPhotos);
        attachDisplayMedia(result, outPhotos);
        if (shouldExposeReviewCandidates(request.contract()) && !reviewCandidates.isEmpty()) {
            result.set(REVIEW_CANDIDATES_FIELD, reviewCandidates);
        }
        addInspectNext(result);
        attachFullImageForSingleResult(result, request.contract());
        return result;
    }

    private void addRealtimePhoto(ScoredPhoto hit,
                                  int rank,
                                  int finalTake,
                                  ArrayNode outPhotos,
                                  ArrayNode reviewCandidates) {
        ObjectNode copy = hit.photo().deepCopy();
        copy.put("rank", rank);
        copy.put("semantic_score", round(hit.semanticScore()));
        copy.put("server_visual_score", round(hit.visualScore()));
        copy.put("device_visual_score", round(hit.deviceVisualScore()));
        copy.put("recency_score", round(hit.recencyScore()));
        copy.put(MATCH_SCORE_FIELD, round(hit.totalScore()));
        copy.put(MATCH_REASON_FIELD, hit.reason());
        copy.put(CANDIDATE_ONLY_FIELD, rank > finalTake);
        if (rank <= finalTake) {
            copy.put(SOURCE_FIELD, "realtime_scan");
            outPhotos.add(copy);
        } else {
            reviewCandidates.add(withoutBinaryFields(copy));
        }
    }

    private ExecutionResult emitTextResult(ObjectNode result, ChatEventSink sink) {
        emitToolCallResult(result, sink);
        return ExecutionResult.text(result.toString());
    }

    private ExecutionResult emitVisionResult(ObjectNode result, ChatEventSink sink) {
        emitToolCallResult(result, sink);
        return textOrVision(result);
    }

    private void emitToolCallResult(ObjectNode result, ChatEventSink sink) {
        if (sink != null) {
            sink.emit(SseEvent.toolCallResult(mapper, TOOL_NAME, result));
        }
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
                    stringFilter(args, BUCKET_ID_FIELD),
                    stringFilter(args, NAME_CONTAINS_FIELD),
                    longFilter(args, DATE_AFTER_FIELD),
                    longFilter(args, DATE_BEFORE_FIELD),
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
            put(photo, BUCKET_ID_FIELD, hit.bucketId());
            put(photo, "bucket_name", hit.bucketName());
            put(photo, DATE_TAKEN_MS_FIELD, hit.dateTakenMs());
            put(photo, DATE_MODIFIED_SEC_FIELD, hit.dateModifiedSec());
            put(photo, "size_bytes", hit.sizeBytes());
            put(photo, "width", hit.width());
            put(photo, "height", hit.height());
            put(photo, "mime_type", hit.mimeType());
            put(photo, "content_hash", hit.contentHash());
            put(photo, "thumb_b64", hit.thumbB64());
            put(photo, "embedding_model", hit.embeddingModel());
            put(photo, EMBEDDING_DIM_FIELD, hit.embeddingDim());
            photo.put("rank", i + 1);
            photo.put("distance", round((float) hit.distance()));
            photo.put(MATCH_SCORE_FIELD, round((float) hit.score()));
            photo.put(MATCH_REASON_FIELD, "photo_index_embedding");
            photo.put(CANDIDATE_ONLY_FIELD, i >= finalTake);
            photo.put(SOURCE_FIELD, "photo_index");
            if (i < finalTake) {
                outPhotos.add(photo);
            } else {
                reviewCandidates.add(withoutBinaryFields(photo));
            }
        }

        ObjectNode result = selectedResult(query, outPhotos.size(), contract.resultLimit(), contract.reviewLimit(), take, contract);
        result.put(SEMANTIC_ENGINE_FIELD, "photo_index");
        result.put(FALLBACK_REALTIME_FIELD, false);
        result.put("embedding_model", photoEmbeddingService.model());
        result.put(EMBEDDING_DIM_FIELD, photoEmbeddingService.dim());
        result.put(MIN_SCORE_FIELD, round((float) contract.minScore()));
        result.set(PHOTOS_FIELD, outPhotos);
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
                SEMANTIC, RELEVANCE, "desc", "hidden_candidates");
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
            float visualScore = visualLabelScore(queryTerms, copy.path(VISUAL_LABELS_FIELD));
            copy.put("rank", i + 1);
            copy.put(MATCH_REASON_FIELD, reason);
            copy.put("server_visual_score", round(visualScore));
            copy.put(MATCH_SCORE_FIELD, round((float) fallbackScore(queryTerms, copy)));
            copy.put(CANDIDATE_ONLY_FIELD, i >= finalTake);
            if (i < finalTake) {
                copy.put(SOURCE_FIELD, "realtime_fallback");
                arr.add(copy);
            } else {
                reviewCandidates.add(withoutBinaryFields(copy));
            }
        }
        ObjectNode out = selectedResult(query, arr.size(), contract.resultLimit(), contract.reviewLimit(), take, contract);
        out.put(SCANNED_FIELD, candidateRoot.path(SCANNED_FIELD).asInt(ordered.size()));
        out.put(SEMANTIC_ENGINE_FIELD, "local_text_visual_fallback");
        out.set(PHOTOS_FIELD, arr);
        attachDisplayMedia(out, arr);
        if (shouldExposeReviewCandidates(contract) && !reviewCandidates.isEmpty()) {
            out.set(REVIEW_CANDIDATES_FIELD, reviewCandidates);
        }
        addInspectNext(out);
        attachFullImageForSingleResult(out, contract);
        return out;
    }

    private ObjectNode selectedResult(String query,
                                      int count,
                                      int requestedLimit,
                                      int reviewLimit,
                                      int reviewedCount,
                                      SearchContract contract) {
        ObjectNode result = mapper.createObjectNode();
        result.put("ok", true);
        result.put(SCHEMA_VERSION_FIELD, AGENT_TOOL_CONTRACT_VERSION);
        result.put("tool", TOOL_NAME);
        result.put(RESULT_TYPE_FIELD, SELECTED_RESULT_TYPE);
        result.put(QUERY_FIELD, query);
        result.put(COUNT_FIELD, count);
        result.put(REQUESTED_LIMIT_FIELD, requestedLimit);
        result.put(REVIEW_LIMIT_FIELD, reviewLimit);
        result.put(REVIEWED_COUNT_FIELD, reviewedCount);
        result.put(CANDIDATE_K_FIELD, contract.candidateK());
        result.put(CANDIDATE_ONLY_FIELD, false);
        result.put(DISPLAY_POLICY_FIELD, resultDisplayPolicy(contract));
        ObjectNode ranking = mapper.createObjectNode();
        ranking.put("mode", contract.rankingMode());
        ranking.put(CANDIDATE_K_FIELD, contract.candidateK());
        ranking.put(MIN_SCORE_FIELD, round((float) contract.minScore()));
        result.set(RANKING_FIELD, ranking);
        ObjectNode sort = mapper.createObjectNode();
        sort.put("by", contract.sortBy());
        sort.put(DIRECTION_FIELD, contract.sortDirection());
        result.set("sort", sort);
        return result;
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
        String id = firstText(photo, "id", MEDIA_STORE_ID_FIELD, "mediaStoreId");
        String mediaStoreId = firstText(photo, MEDIA_STORE_ID_FIELD, "mediaStoreId", "id");
        String assetId = firstText(photo, "photo_asset_id", "asset_id", "assetId");

        item.put("kind", "image");
        put(item, "id", id);
        put(item, MEDIA_STORE_ID_FIELD, mediaStoreId);
        put(item, "photo_asset_id", assetId);
        copyField(photo, item, "device_id");
        copyField(photo, item, "name");
        copyField(photo, item, "mime_type");
        copyField(photo, item, "width");
        copyField(photo, item, "height");
        copyField(photo, item, DATE_TAKEN_MS_FIELD);
        copyField(photo, item, DATE_MODIFIED_SEC_FIELD);
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
        JsonNode photos = result.path(PHOTOS_FIELD);
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
        JsonNode photos = result.path(PHOTOS_FIELD);
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
        if (COLLAPSED_CANDIDATES.equals(contract.displayPolicy())) {
            return COLLAPSED_CANDIDATES;
        }
        return contract.resultLimit() == 1 ? "show_primary" : "show_grid";
    }

    private static boolean shouldExposeReviewCandidates(SearchContract contract) {
        return COLLAPSED_CANDIDATES.equals(contract.displayPolicy());
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
        JsonNode photos = result.path(PHOTOS_FIELD);
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
        result.set(PHOTOS_FIELD, stripped);
    }

    private JsonNode parseJsonArgs(ToolUseBlock tu) {
        try {
            Object raw = tu._input();
            if (raw == null) return mapper.createObjectNode();
            JsonNode node = mapper.valueToTree(raw);
            if (node != null && node.isTextual()) {
                return parseTextualArgs(node.asText());
            }
            return node == null || node.isNull() ? mapper.createObjectNode() : node;
        } catch (Exception e) {
            ObjectNode fallback = mapper.createObjectNode();
            fallback.put("_parse_error", e.getMessage());
            return fallback;
        }
    }

    private JsonNode parseTextualArgs(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception ignore) {
            return mapper.createObjectNode();
        }
    }

    static void copyIfPresent(JsonNode src, ObjectNode dst, String key) {
        JsonNode value = src.get(key);
        if (value != null && !value.isNull() && !isEmptyPhotoFilter(key, value)) {
            dst.set(key, value);
        }
    }

    private static boolean isEmptyPhotoFilter(String key, JsonNode value) {
        if ((BUCKET_ID_FIELD.equals(key) || NAME_CONTAINS_FIELD.equals(key))
                && value.isTextual() && value.asText().trim().isEmpty()) {
            return true;
        }
        return (DATE_AFTER_FIELD.equals(key) || DATE_BEFORE_FIELD.equals(key))
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
        if (rankingMode == null && args.has(RANKING_FIELD) && args.path(RANKING_FIELD).isObject()) {
            rankingMode = stringFilter(args.path(RANKING_FIELD), "mode");
        }
        if (sortBy == null && args.has("sort") && args.path("sort").isObject()) {
            sortBy = stringFilter(args.path("sort"), "by");
        }
        if (sortDirection == null && args.has("sort") && args.path("sort").isObject()) {
            sortDirection = stringFilter(args.path("sort"), DIRECTION_FIELD);
        }

        boolean latestIntent = latestIntent(query);
        String normalizedSortBy = normalizeSortBy(sortBy);
        if (RELEVANCE.equals(normalizedSortBy) && latestIntent) {
            normalizedSortBy = DATE_TAKEN;
        }
        String normalizedRanking = normalizeRankingMode(rankingMode);
        if (latestIntent && DATE_TAKEN.equals(normalizedSortBy) && SEMANTIC.equals(normalizedRanking)) {
            normalizedRanking = SEMANTIC_THEN_SORT;
        }
        String normalizedDirection = normalizeSortDirection(sortDirection);
        int minCandidateK = Math.max(limit, reviewLimit);
        int candidateK = clamp(args.path(CANDIDATE_K_FIELD).asInt(minCandidateK), minCandidateK, 50);
        double minScore = args.path(MIN_SCORE_FIELD).isNumber()
                ? clampScore(args.path(MIN_SCORE_FIELD).asDouble())
                : indexMinScore;
        String display = stringFilter(args, "display");
        String displayPolicy = "show_candidates".equals(display)
                ? COLLAPSED_CANDIDATES
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
        if (SEMANTIC_THEN_SORT.equals(clean) || "sort_then_semantic".equals(clean)) {
            return clean;
        }
        return SEMANTIC;
    }

    private static String normalizeSortBy(String value) {
        String clean = cleanLower(value);
        if (DATE_TAKEN.equals(clean)
                || DATE_MODIFIED.equals(clean)
                || "created_at".equals(clean)
                || "updated_at".equals(clean)
                || "name".equals(clean)) {
            return clean;
        }
        return RELEVANCE;
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
        float visualScore = visualLabelScore(queryTerms, photo.path(VISUAL_LABELS_FIELD));
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
        if (!SEMANTIC_THEN_SORT.equals(contract.rankingMode()) || RELEVANCE.equals(contract.sortBy())) {
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
            case DATE_MODIFIED -> Comparator.comparing(p -> p.photo().path(DATE_MODIFIED_SEC_FIELD).asLong(0L));
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
        if (!SEMANTIC_THEN_SORT.equals(contract.rankingMode()) || RELEVANCE.equals(contract.sortBy())) {
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
            case DATE_MODIFIED -> Comparator.comparingLong(n -> n.path(DATE_MODIFIED_SEC_FIELD).asLong(0L));
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

    private record RealtimeSearchRequest(JsonNode args,
                                         String query,
                                         int limit,
                                         int reviewLimit,
                                         int scanLimit,
                                         SearchContract contract) {}

    private record QueryEmbeddingResult(float[] value, String failureReason) {
        private boolean failed() {
            return failureReason != null;
        }
    }

    private record CandidateEmbeddingResult(float score, String reason) {}

    private record SearchContract(int resultLimit,
                                  int reviewLimit,
                                  int candidateK,
                                  double minScore,
                                  String rankingMode,
                                  String sortBy,
                                  String sortDirection,
                                  String displayPolicy) {}
}
