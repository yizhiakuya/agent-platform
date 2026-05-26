package com.agentplatform.chat.service;

import com.agentplatform.api.chat.PendingPhotoAssetDto;
import com.agentplatform.api.chat.PhotoAssetBatchResponse;
import com.agentplatform.api.chat.PhotoAssetDto;
import com.agentplatform.api.chat.PhotoAssetReconcileResponse;
import com.agentplatform.api.chat.PhotoAssetSearchRequest;
import com.agentplatform.api.chat.PhotoAssetSearchResult;
import com.agentplatform.api.chat.PhotoAssetUpsertRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class PhotoIndexService {

    private static final Logger log = LoggerFactory.getLogger(PhotoIndexService.class);

    private static final int MAX_BATCH = 100;
    private static final int MAX_TOP_K = 50;
    private static final int MAX_PENDING = 200;
    private static final int MAX_RECONCILE_IDS = 50_000;
    private static final String RANKING_SEMANTIC = "semantic";
    private static final String RANKING_SEMANTIC_THEN_SORT = "semantic_then_sort";
    private static final String RANKING_SORT_THEN_SEMANTIC = "sort_then_semantic";
    private static final String SORT_BY_RELEVANCE = "relevance";
    private static final String SORT_BY_DATE_TAKEN = "date_taken";
    private static final String SORT_BY_DATE_MODIFIED = "date_modified";
    private static final String SORT_BY_CREATED_AT = "created_at";
    private static final String SORT_BY_UPDATED_AT = "updated_at";
    private static final String SORT_BY_NAME = "name";
    private static final String COL_DEVICE_ID = "device_id";
    private static final String COL_MEDIA_STORE_ID = "media_store_id";
    private static final String COL_MIME_TYPE = "mime_type";
    private static final String COL_THUMB_B64 = "thumb_b64";
    private static final String COL_USER_ID = "user_id";
    private static final String COL_UPDATED_AT = "updated_at";
    private static final String PHOTO_SEARCH_SQL = """
            WITH ranked_photo_assets AS (
                SELECT pa.id, pa.device_id, pa.media_store_id, pa.name,
                       pa.bucket_id, pa.bucket_name, pa.date_taken_ms,
                       pa.date_modified_sec, pa.size_bytes, pa.width, pa.height,
                       pa.mime_type, pa.content_hash, pa.thumb_b64,
                       pa.indexed_at, pa.updated_at,
                       pe.embedding_model, pe.embedding_dim,
                       pe.embedding OPERATOR(public.<=>) ?::public.vector AS distance
                FROM photo_assets pa
                JOIN photo_asset_embeddings pe ON pe.asset_id = pa.id
                WHERE pa.user_id = ?
                  AND pa.deleted_at IS NULL
                  AND (?::text IS NULL OR pa.bucket_id = ?::text)
                  AND (?::text IS NULL OR lower(pa.name) LIKE ?::text)
                  AND (?::bigint IS NULL OR pa.date_taken_ms >= ?::bigint)
                  AND (?::bigint IS NULL OR pa.date_taken_ms <= ?::bigint)
                  AND (?::double precision <= 0.0
                       OR 1.0 - (pe.embedding OPERATOR(public.<=>) ?::public.vector) >= ?::double precision)
                ORDER BY
                    CASE WHEN ?::boolean THEN date_modified_sec END ASC NULLS LAST,
                    CASE WHEN ?::boolean THEN date_modified_sec END DESC NULLS LAST,
                    CASE WHEN ?::boolean THEN indexed_at END ASC NULLS LAST,
                    CASE WHEN ?::boolean THEN indexed_at END DESC NULLS LAST,
                    CASE WHEN ?::boolean THEN updated_at END ASC NULLS LAST,
                    CASE WHEN ?::boolean THEN updated_at END DESC NULLS LAST,
                    CASE WHEN ?::boolean THEN name END ASC NULLS LAST,
                    CASE WHEN ?::boolean THEN name END DESC NULLS LAST,
                    CASE WHEN ?::boolean THEN date_taken_ms END ASC NULLS LAST,
                    CASE WHEN ?::boolean THEN date_taken_ms END DESC NULLS LAST,
                    CASE WHEN ?::boolean THEN pe.embedding OPERATOR(public.<=>) ?::public.vector END ASC
                LIMIT ?
            ),
            qualified_photo_assets AS (
                SELECT *
                FROM (
                    SELECT ranked_photo_assets.*,
                           max(1.0 - distance) OVER () AS best_score
                    FROM ranked_photo_assets
                ) scored
                WHERE NOT ?::boolean
                   OR 1.0 - distance >= GREATEST(
                        ?::double precision,
                        best_score - ?::double precision,
                        best_score * ?::double precision
                   )
            )
            SELECT id, device_id, media_store_id, name,
                   bucket_id, bucket_name, date_taken_ms,
                   date_modified_sec, size_bytes, width, height,
                   mime_type, content_hash, thumb_b64,
                   indexed_at, updated_at,
                   embedding_model, embedding_dim, distance
            FROM qualified_photo_assets
            ORDER BY
                CASE WHEN ?::boolean THEN distance END ASC,
                CASE WHEN ?::boolean THEN date_taken_ms END DESC NULLS LAST,
                CASE WHEN ?::boolean THEN date_modified_sec END ASC NULLS LAST,
                CASE WHEN ?::boolean THEN date_modified_sec END DESC NULLS LAST,
                CASE WHEN ?::boolean THEN indexed_at END ASC NULLS LAST,
                CASE WHEN ?::boolean THEN indexed_at END DESC NULLS LAST,
                CASE WHEN ?::boolean THEN updated_at END ASC NULLS LAST,
                CASE WHEN ?::boolean THEN updated_at END DESC NULLS LAST,
                CASE WHEN ?::boolean THEN name END ASC NULLS LAST,
                CASE WHEN ?::boolean THEN name END DESC NULLS LAST,
                CASE WHEN ?::boolean THEN date_taken_ms END ASC NULLS LAST,
                CASE WHEN ?::boolean THEN date_taken_ms END DESC NULLS LAST,
                CASE WHEN ?::boolean THEN distance END ASC
            LIMIT ?
            """;
    static final double SEMANTIC_THEN_SORT_MAX_SCORE_DROP = 0.06d;
    static final double SEMANTIC_THEN_SORT_MIN_SCORE_RATIO = 0.80d;

    private final JdbcTemplate jdbc;

    public PhotoIndexService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public PhotoAssetBatchResponse upsertBatch(UUID userId, UUID deviceId, List<PhotoAssetUpsertRequest> assets) {
        if (userId == null || deviceId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId and deviceId are required");
        }
        if (assets == null || assets.isEmpty()) {
            return new PhotoAssetBatchResponse(0, 0, 0);
        }
        if (assets.size() > MAX_BATCH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "max photo batch size is " + MAX_BATCH);
        }

        int upserted = 0;
        for (PhotoAssetUpsertRequest req : assets) {
            upserted += upsertAsset(userId, deviceId, req);
        }

        int pending = pendingCount(userId);
        log.debug("Upserted {} photo asset row(s) for user {} device {} (pending={})",
                upserted, userId, deviceId, pending);
        return new PhotoAssetBatchResponse(assets.size(), upserted, pending);
    }

    private int upsertAsset(UUID userId, UUID deviceId, PhotoAssetUpsertRequest req) {
        if (req == null) {
            return 0;
        }
        String mediaStoreId = clean(req.mediaStoreId());
        if (mediaStoreId == null) {
            return 0;
        }

        String thumb = clean(req.thumbB64());
        String contentHash = clean(req.contentHash());
        List<UUID> staleEmbeddingIds = staleEmbeddingIds(deviceId, mediaStoreId, contentHash, thumb);
        int rows = jdbc.update("""
                INSERT INTO photo_assets (
                    id, user_id, device_id, media_store_id, name,
                    bucket_id, bucket_name, date_taken_ms, date_modified_sec,
                    size_bytes, width, height, mime_type, content_hash,
                    thumb_b64, indexed_at, updated_at, deleted_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now(), NULL)
                ON CONFLICT (device_id, media_store_id) DO UPDATE SET
                    user_id = EXCLUDED.user_id,
                    name = EXCLUDED.name,
                    bucket_id = EXCLUDED.bucket_id,
                    bucket_name = EXCLUDED.bucket_name,
                    date_taken_ms = EXCLUDED.date_taken_ms,
                    date_modified_sec = EXCLUDED.date_modified_sec,
                    size_bytes = EXCLUDED.size_bytes,
                    width = EXCLUDED.width,
                    height = EXCLUDED.height,
                    mime_type = EXCLUDED.mime_type,
                    content_hash = EXCLUDED.content_hash,
                    thumb_b64 = EXCLUDED.thumb_b64,
                    updated_at = now(),
                    deleted_at = NULL
                """,
                UUID.randomUUID(),
                userId,
                deviceId,
                mediaStoreId,
                assetName(req, mediaStoreId),
                clean(req.bucketId()),
                clean(req.bucketName()),
                req.dateTakenMs(),
                req.dateModifiedSec(),
                req.sizeBytes(),
                req.width(),
                req.height(),
                clean(req.mimeType()),
                contentHash,
                thumb);
        invalidateStaleEmbeddings(deviceId, mediaStoreId, staleEmbeddingIds);
        return rows;
    }

    private List<UUID> staleEmbeddingIds(UUID deviceId, String mediaStoreId, String contentHash, String thumb) {
        return jdbc.queryForList("""
                SELECT id
                FROM photo_assets
                WHERE device_id = ?
                  AND media_store_id = ?
                  AND (
                      content_hash IS DISTINCT FROM ?
                      OR thumb_b64 IS DISTINCT FROM ?
                  )
                """,
                UUID.class,
                deviceId,
                mediaStoreId,
                contentHash,
                thumb);
    }

    private void invalidateStaleEmbeddings(UUID deviceId, String mediaStoreId, List<UUID> staleEmbeddingIds) {
        if (staleEmbeddingIds.isEmpty()) {
            return;
        }
        int deleted = 0;
        for (UUID staleEmbeddingId : staleEmbeddingIds) {
            deleted += jdbc.update("DELETE FROM photo_asset_embeddings WHERE asset_id = ?", staleEmbeddingId);
        }
        log.debug("Invalidated {} stale photo embedding row(s) for device {} mediaStoreId {}",
                deleted, deviceId, mediaStoreId);
    }

    private static String assetName(PhotoAssetUpsertRequest req, String mediaStoreId) {
        String name = clean(req.name());
        return name == null ? "image_" + mediaStoreId : name;
    }

    @Transactional
    public PhotoAssetReconcileResponse reconcileDeviceAssets(UUID userId, UUID deviceId, List<String> mediaStoreIds) {
        if (userId == null || deviceId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId and deviceId are required");
        }
        if (mediaStoreIds == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mediaStoreIds are required");
        }
        List<String> currentIds = mediaStoreIds.stream()
                .map(PhotoIndexService::clean)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (currentIds.size() > MAX_RECONCILE_IDS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "max photo reconcile size is " + MAX_RECONCILE_IDS);
        }

        List<UUID> deletedAssetIds;
        if (currentIds.isEmpty()) {
            deletedAssetIds = jdbc.queryForList("""
                    UPDATE photo_assets
                    SET deleted_at = now(), updated_at = now(), thumb_b64 = NULL
                    WHERE user_id = ?
                      AND device_id = ?
                      AND deleted_at IS NULL
                    RETURNING id
                    """,
                    UUID.class,
                    userId,
                    deviceId);
        } else {
            String sql = """
                    UPDATE photo_assets
                    SET deleted_at = now(), updated_at = now(), thumb_b64 = NULL
                    WHERE user_id = ?
                      AND device_id = ?
                      AND deleted_at IS NULL
                      AND NOT (media_store_id = ANY (?::text[]))
                    RETURNING id
                    """;
            deletedAssetIds = jdbc.query(con -> {
                PreparedStatement ps = con.prepareStatement(sql);
                ps.setObject(1, userId);
                ps.setObject(2, deviceId);
                ps.setArray(3, con.createArrayOf("text", currentIds.toArray(String[]::new)));
                return ps;
            }, (rs, rowNum) -> (UUID) rs.getObject("id"));
        }
        int deleted = deletedAssetIds.size();
        for (UUID assetId : deletedAssetIds) {
            jdbc.update("DELETE FROM photo_asset_embeddings WHERE asset_id = ?", assetId);
        }
        log.debug("Reconciled photo index for user {} device {} (current={}, deleted={})",
                userId, deviceId, currentIds.size(), deleted);
        return new PhotoAssetReconcileResponse(currentIds.size(), deleted);
    }

    public List<PendingPhotoAssetDto> listPending(int limit) {
        int cap = Math.max(1, Math.min(limit <= 0 ? 50 : limit, MAX_PENDING));
        return jdbc.query("""
                SELECT pa.id, pa.user_id, pa.device_id, pa.media_store_id,
                       pa.name, pa.mime_type, pa.thumb_b64
                FROM photo_assets pa
                LEFT JOIN photo_asset_embeddings pe ON pe.asset_id = pa.id
                WHERE pa.deleted_at IS NULL
                  AND pa.thumb_b64 IS NOT NULL
                  AND length(pa.thumb_b64) > 0
                  AND pe.asset_id IS NULL
                ORDER BY pa.updated_at DESC
                LIMIT ?
                """,
                PENDING_ROW_MAPPER,
                cap);
    }

    @Transactional
    public void saveEmbedding(UUID assetId, float[] embedding, String embeddingModel, int embeddingDim) {
        if (assetId == null || embedding == null || embedding.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "assetId and embedding are required");
        }
        String model = clean(embeddingModel);
        if (model == null) model = "unknown";
        int dim = embeddingDim > 0 ? embeddingDim : embedding.length;
        jdbc.update("""
                INSERT INTO photo_asset_embeddings (asset_id, embedding, embedding_model, embedding_dim, embedded_at)
                VALUES (?, ?::public.vector, ?, ?, now())
                ON CONFLICT (asset_id) DO UPDATE SET
                    embedding = EXCLUDED.embedding,
                    embedding_model = EXCLUDED.embedding_model,
                    embedding_dim = EXCLUDED.embedding_dim,
                    embedded_at = now()
                """,
                assetId,
                MemoryService.toVectorLiteral(embedding),
                model,
                dim);
    }

    public List<PhotoAssetSearchResult> search(UUID userId,
                                               float[] queryEmbedding,
                                               int topK,
                                               String bucketId,
                                               Long dateAfter,
                                               Long dateBefore,
                                               Double minScore) {
        return search(new PhotoAssetSearchRequest(userId, queryEmbedding, topK, bucketId,
                dateAfter, dateBefore, minScore));
    }

    public List<PhotoAssetSearchResult> search(PhotoAssetSearchRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request is required");
        }
        float[] queryEmbedding = request.queryEmbedding();
        if (request.userId() == null || queryEmbedding == null || queryEmbedding.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId and queryEmbedding are required");
        }
        SearchOptions options = SearchOptions.from(request);
        String vec = MemoryService.toVectorLiteral(queryEmbedding);
        return jdbc.query(PHOTO_SEARCH_SQL, SEARCH_ROW_MAPPER, searchArgs(request, options, vec));
    }

    private static Object[] searchArgs(PhotoAssetSearchRequest request, SearchOptions options, String vec) {
        List<Object> args = new ArrayList<>();
        args.add(vec);
        args.add(request.userId());

        String cleanBucket = clean(request.bucketId());
        args.add(cleanBucket);
        args.add(cleanBucket);

        String cleanNameContains = clean(request.nameContains());
        String nameLike = cleanNameContains == null
                ? null
                : "%" + cleanNameContains.toLowerCase(Locale.ROOT) + "%";
        args.add(nameLike);
        args.add(nameLike);

        Long dateAfter = request.dateAfter() != null && request.dateAfter() > 0L ? request.dateAfter() : null;
        args.add(dateAfter);
        args.add(dateAfter);

        Long dateBefore = request.dateBefore() != null && request.dateBefore() > 0L ? request.dateBefore() : null;
        args.add(dateBefore);
        args.add(dateBefore);

        args.add(options.threshold());
        args.add(vec);
        args.add(options.threshold());

        boolean sortThenSemantic = options.sortThenSemantic();
        addMetadataOrderArgs(args, sortThenSemantic, options.candidateSort(), options.direction());
        args.add(!sortThenSemantic);
        args.add(vec);
        args.add(options.candidateK());

        args.add(options.qualifyBeforeSort());
        args.add(options.threshold());
        args.add(SEMANTIC_THEN_SORT_MAX_SCORE_DROP);
        args.add(SEMANTIC_THEN_SORT_MIN_SCORE_RATIO);

        boolean finalRelevanceOrder = options.finalRelevanceOrder();
        boolean finalMetadataOrder = !finalRelevanceOrder;
        args.add(finalRelevanceOrder);
        args.add(finalRelevanceOrder);
        addMetadataOrderArgs(args, finalMetadataOrder, options.sort(), options.direction());
        args.add(finalMetadataOrder);
        args.add(options.finalLimit());
        return args.toArray();
    }

    private static void addMetadataOrderArgs(List<Object> args,
                                             boolean enabled,
                                             String sortBy,
                                             String direction) {
        String metadataSort = SORT_BY_RELEVANCE.equals(sortBy) ? SORT_BY_DATE_TAKEN : sortBy;
        boolean asc = "asc".equals(direction);
        args.add(enabled && SORT_BY_DATE_MODIFIED.equals(metadataSort) && asc);
        args.add(enabled && SORT_BY_DATE_MODIFIED.equals(metadataSort) && !asc);
        args.add(enabled && SORT_BY_CREATED_AT.equals(metadataSort) && asc);
        args.add(enabled && SORT_BY_CREATED_AT.equals(metadataSort) && !asc);
        args.add(enabled && SORT_BY_UPDATED_AT.equals(metadataSort) && asc);
        args.add(enabled && SORT_BY_UPDATED_AT.equals(metadataSort) && !asc);
        args.add(enabled && SORT_BY_NAME.equals(metadataSort) && asc);
        args.add(enabled && SORT_BY_NAME.equals(metadataSort) && !asc);
        args.add(enabled && SORT_BY_DATE_TAKEN.equals(metadataSort) && asc);
        args.add(enabled && SORT_BY_DATE_TAKEN.equals(metadataSort) && !asc);
    }

    public PhotoAssetDto get(UUID userId, UUID assetId) {
        if (userId == null || assetId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId and assetId are required");
        }
        List<PhotoAssetDto> rows = jdbc.query("""
                SELECT pa.id, pa.user_id, pa.device_id, pa.media_store_id, pa.name,
                       pa.bucket_id, pa.bucket_name, pa.date_taken_ms,
                       pa.date_modified_sec, pa.size_bytes, pa.width, pa.height,
                       pa.mime_type, pa.content_hash, pa.thumb_b64,
                       pe.embedding_model, pe.embedding_dim,
                       pa.indexed_at, pa.updated_at
                FROM photo_assets pa
                LEFT JOIN photo_asset_embeddings pe ON pe.asset_id = pa.id
                WHERE pa.user_id = ? AND pa.id = ? AND pa.deleted_at IS NULL
                """,
                ASSET_ROW_MAPPER,
                userId,
                assetId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "photo asset not found");
        }
        return rows.get(0);
    }

    private int pendingCount(UUID userId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM photo_assets pa
                LEFT JOIN photo_asset_embeddings pe ON pe.asset_id = pa.id
                WHERE pa.user_id = ?
                  AND pa.deleted_at IS NULL
                  AND pa.thumb_b64 IS NOT NULL
                  AND length(pa.thumb_b64) > 0
                  AND pe.asset_id IS NULL
                """,
                Integer.class,
                userId);
        return n == null ? 0 : n;
    }

    private static String normalizeRankingMode(String value) {
        String clean = cleanLower(value);
        if (RANKING_SEMANTIC_THEN_SORT.equals(clean)) return RANKING_SEMANTIC_THEN_SORT;
        if (RANKING_SORT_THEN_SEMANTIC.equals(clean)) return RANKING_SORT_THEN_SEMANTIC;
        return RANKING_SEMANTIC;
    }

    private static String normalizeSortBy(String value) {
        String clean = cleanLower(value);
        if (SORT_BY_DATE_TAKEN.equals(clean)
                || SORT_BY_DATE_MODIFIED.equals(clean)
                || SORT_BY_CREATED_AT.equals(clean)
                || SORT_BY_UPDATED_AT.equals(clean)
                || SORT_BY_NAME.equals(clean)) {
            return clean;
        }
        return SORT_BY_RELEVANCE;
    }

    private static String normalizeSortDirection(String value) {
        return "asc".equals(cleanLower(value)) ? "asc" : "desc";
    }

    private static boolean shouldQualifyBeforeMetadataSort(String rankingMode, String sortBy) {
        return RANKING_SEMANTIC_THEN_SORT.equals(rankingMode) && !SORT_BY_RELEVANCE.equals(sortBy);
    }

    static double qualifyingScoreThreshold(double bestScore, double minScore) {
        double floor = Double.isFinite(minScore) ? Math.max(0.0d, Math.min(0.99d, minScore)) : 0.0d;
        if (!Double.isFinite(bestScore)) {
            return floor;
        }
        return Math.max(floor, Math.max(
                bestScore - SEMANTIC_THEN_SORT_MAX_SCORE_DROP,
                bestScore * SEMANTIC_THEN_SORT_MIN_SCORE_RATIO));
    }

    private record SearchOptions(int candidateK,
                                 int finalLimit,
                                 double threshold,
                                 String ranking,
                                 String sort,
                                 String direction) {
        static SearchOptions from(PhotoAssetSearchRequest request) {
            int candidateK = Math.max(1, Math.min(request.topK() <= 0 ? 8 : request.topK(), MAX_TOP_K));
            int finalLimit = Math.max(1, Math.min(
                    request.resultLimit() == null || request.resultLimit() <= 0 ? candidateK : request.resultLimit(),
                    MAX_TOP_K));
            double threshold = request.minScore() == null
                    ? 0.0d
                    : Math.max(0.0d, Math.min(0.99d, request.minScore()));
            String ranking = normalizeRankingMode(request.rankingMode());
            String sort = normalizeSortBy(request.sortBy());
            String direction = normalizeSortDirection(request.sortDirection());
            return new SearchOptions(candidateK, finalLimit, threshold, ranking, sort, direction);
        }

        boolean qualifyBeforeSort() {
            return shouldQualifyBeforeMetadataSort(ranking, sort);
        }

        boolean sortThenSemantic() {
            return RANKING_SORT_THEN_SEMANTIC.equals(ranking);
        }

        String candidateSort() {
            return sort;
        }

        boolean finalRelevanceOrder() {
            return sortThenSemantic() || SORT_BY_RELEVANCE.equals(sort);
        }
    }

    private static String cleanLower(String value) {
        String clean = clean(value);
        return clean == null ? null : clean.toLowerCase(Locale.ROOT);
    }

    private static String clean(String value) {
        if (value == null) return null;
        String out = value.trim();
        return out.isEmpty() ? null : out;
    }

    private static OffsetDateTime odt(Timestamp ts) {
        return ts == null ? null : OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC);
    }

    private static final RowMapper<PendingPhotoAssetDto> PENDING_ROW_MAPPER = (rs, rn) ->
            new PendingPhotoAssetDto(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject(COL_USER_ID),
                    (UUID) rs.getObject(COL_DEVICE_ID),
                    rs.getString(COL_MEDIA_STORE_ID),
                    rs.getString("name"),
                    rs.getString(COL_MIME_TYPE),
                    rs.getString(COL_THUMB_B64)
            );

    private static final RowMapper<PhotoAssetSearchResult> SEARCH_ROW_MAPPER = (rs, rn) -> {
        double distance = rs.getDouble("distance");
        double score = 1.0 - distance;
        return new PhotoAssetSearchResult(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject(COL_DEVICE_ID),
                rs.getString(COL_MEDIA_STORE_ID),
                rs.getString("name"),
                rs.getString("bucket_id"),
                rs.getString("bucket_name"),
                boxedLong(rs, "date_taken_ms"),
                boxedLong(rs, "date_modified_sec"),
                boxedLong(rs, "size_bytes"),
                boxedInt(rs, "width"),
                boxedInt(rs, "height"),
                rs.getString(COL_MIME_TYPE),
                rs.getString("content_hash"),
                rs.getString(COL_THUMB_B64),
                rs.getString("embedding_model"),
                boxedInt(rs, "embedding_dim"),
                distance,
                score
        );
    };

    private static final RowMapper<PhotoAssetDto> ASSET_ROW_MAPPER = (rs, rn) ->
            new PhotoAssetDto(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject(COL_USER_ID),
                    (UUID) rs.getObject(COL_DEVICE_ID),
                    rs.getString(COL_MEDIA_STORE_ID),
                    rs.getString("name"),
                    rs.getString("bucket_id"),
                    rs.getString("bucket_name"),
                    boxedLong(rs, "date_taken_ms"),
                    boxedLong(rs, "date_modified_sec"),
                    boxedLong(rs, "size_bytes"),
                    boxedInt(rs, "width"),
                    boxedInt(rs, "height"),
                    rs.getString(COL_MIME_TYPE),
                    rs.getString("content_hash"),
                    rs.getString(COL_THUMB_B64),
                    rs.getString("embedding_model"),
                    boxedInt(rs, "embedding_dim"),
                    odt(rs.getTimestamp("indexed_at")),
                    odt(rs.getTimestamp(COL_UPDATED_AT))
            );

    private static Long boxedLong(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        long value = rs.getLong(col);
        return rs.wasNull() ? null : value;
    }

    private static Integer boxedInt(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        int value = rs.getInt(col);
        return rs.wasNull() ? null : value;
    }
}
