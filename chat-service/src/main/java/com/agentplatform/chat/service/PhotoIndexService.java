package com.agentplatform.chat.service;

import com.agentplatform.api.chat.PendingPhotoAssetDto;
import com.agentplatform.api.chat.PhotoAssetBatchResponse;
import com.agentplatform.api.chat.PhotoAssetDto;
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
    private static final String RANKING_SEMANTIC = "semantic";
    private static final String RANKING_SEMANTIC_THEN_SORT = "semantic_then_sort";
    private static final String RANKING_SORT_THEN_SEMANTIC = "sort_then_semantic";
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
            if (req == null) continue;
            String mediaStoreId = clean(req.mediaStoreId());
            if (mediaStoreId == null) continue;
            String name = clean(req.name());
            if (name == null) name = "image_" + mediaStoreId;
            String thumb = clean(req.thumbB64());
            UUID assetId = UUID.randomUUID();
            List<UUID> staleEmbeddingIds = jdbc.queryForList("""
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
                    clean(req.contentHash()),
                    thumb);
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
                    assetId,
                    userId,
                    deviceId,
                    mediaStoreId,
                    name,
                    clean(req.bucketId()),
                    clean(req.bucketName()),
                    req.dateTakenMs(),
                    req.dateModifiedSec(),
                    req.sizeBytes(),
                    req.width(),
                    req.height(),
                    clean(req.mimeType()),
                    clean(req.contentHash()),
                    thumb);
            upserted += rows;
            if (!staleEmbeddingIds.isEmpty()) {
                int deleted = 0;
                for (UUID staleEmbeddingId : staleEmbeddingIds) {
                    deleted += jdbc.update("DELETE FROM photo_asset_embeddings WHERE asset_id = ?", staleEmbeddingId);
                }
                log.debug("Invalidated {} stale photo embedding row(s) for device {} mediaStoreId {}",
                        deleted, deviceId, mediaStoreId);
            }
        }

        int pending = pendingCount(userId);
        log.debug("Upserted {} photo asset row(s) for user {} device {} (pending={})",
                upserted, userId, deviceId, pending);
        return new PhotoAssetBatchResponse(assets.size(), upserted, pending);
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
        return search(userId, queryEmbedding, topK, bucketId, null, dateAfter, dateBefore, minScore,
                null, null, null, null);
    }

    public List<PhotoAssetSearchResult> search(UUID userId,
                                               float[] queryEmbedding,
                                               int topK,
                                               String bucketId,
                                               String nameContains,
                                               Long dateAfter,
                                               Long dateBefore,
                                               Double minScore,
                                               Integer resultLimit,
                                               String rankingMode,
                                               String sortBy,
                                               String sortDirection) {
        if (userId == null || queryEmbedding == null || queryEmbedding.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId and queryEmbedding are required");
        }
        int candidateK = Math.max(1, Math.min(topK <= 0 ? 8 : topK, MAX_TOP_K));
        int finalLimit = Math.max(1, Math.min(resultLimit == null || resultLimit <= 0 ? candidateK : resultLimit, MAX_TOP_K));
        String ranking = normalizeRankingMode(rankingMode);
        String sort = normalizeSortBy(sortBy);
        String direction = normalizeSortDirection(sortDirection);
        String vec = MemoryService.toVectorLiteral(queryEmbedding);
        List<Object> args = new ArrayList<>();
        args.add(vec);
        args.add(userId);

        StringBuilder sql = new StringBuilder("""
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
                """);
        String cleanBucket = clean(bucketId);
        if (cleanBucket != null) {
            sql.append(" AND pa.bucket_id = ?");
            args.add(cleanBucket);
        }
        String cleanNameContains = clean(nameContains);
        if (cleanNameContains != null) {
            sql.append(" AND lower(pa.name) LIKE ?");
            args.add("%" + cleanNameContains.toLowerCase(Locale.ROOT) + "%");
        }
        if (dateAfter != null && dateAfter > 0L) {
            sql.append(" AND pa.date_taken_ms >= ?");
            args.add(dateAfter);
        }
        if (dateBefore != null && dateBefore > 0L) {
            sql.append(" AND pa.date_taken_ms <= ?");
            args.add(dateBefore);
        }
        double threshold = minScore == null ? 0.0d : Math.max(0.0d, Math.min(0.99d, minScore));
        if (threshold > 0.0d) {
            sql.append(" AND 1.0 - (pe.embedding OPERATOR(public.<=>) ?::public.vector) >= ?");
            args.add(vec);
            args.add(threshold);
        }
        if (RANKING_SORT_THEN_SEMANTIC.equals(ranking)) {
            sql.append(metadataOrderClause(sort, direction));
        } else {
            sql.append(" ORDER BY pe.embedding OPERATOR(public.<=>) ?::public.vector");
            args.add(vec);
        }
        sql.append(" LIMIT ?");
        args.add(candidateK);

        boolean qualifyBeforeSort = shouldQualifyBeforeMetadataSort(ranking, sort);
        String sourceCte = qualifyBeforeSort ? "qualified_photo_assets" : "ranked_photo_assets";
        String qualificationCte = "";
        if (qualifyBeforeSort) {
            qualificationCte = """
                    ,
                    qualified_photo_assets AS (
                        SELECT *
                        FROM (
                            SELECT ranked_photo_assets.*,
                                   max(1.0 - distance) OVER () AS best_score
                            FROM ranked_photo_assets
                        ) scored
                        WHERE 1.0 - distance >= GREATEST(
                            ?::double precision,
                            best_score - ?::double precision,
                            best_score * ?::double precision
                        )
                    )
                    """;
            args.add(threshold);
            args.add(SEMANTIC_THEN_SORT_MAX_SCORE_DROP);
            args.add(SEMANTIC_THEN_SORT_MIN_SCORE_RATIO);
        }

        String finalOrder = RANKING_SORT_THEN_SEMANTIC.equals(ranking)
                ? " ORDER BY distance ASC, date_taken_ms DESC NULLS LAST"
                : finalOrderClause(sort, direction);
        String wrapped = """
                WITH ranked_photo_assets AS (
                %s
                )%s
                SELECT *
                FROM %s
                %s
                LIMIT ?
                """.formatted(sql, qualificationCte, sourceCte, finalOrder);
        args.add(finalLimit);

        return jdbc.query(wrapped, SEARCH_ROW_MAPPER, args.toArray());
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

    private static boolean shouldQualifyBeforeMetadataSort(String rankingMode, String sortBy) {
        return RANKING_SEMANTIC_THEN_SORT.equals(rankingMode) && !"relevance".equals(sortBy);
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

    private static String finalOrderClause(String sortBy, String direction) {
        if ("relevance".equals(sortBy)) {
            return " ORDER BY distance ASC, date_taken_ms DESC NULLS LAST";
        }
        return metadataOrderClause(sortBy, direction) + ", distance ASC";
    }

    private static String metadataOrderClause(String sortBy, String direction) {
        boolean asc = "asc".equals(direction);
        String dir = asc ? "ASC" : "DESC";
        return switch (sortBy) {
            case "date_modified" -> " ORDER BY date_modified_sec " + dir + " NULLS LAST";
            case "created_at" -> " ORDER BY indexed_at " + dir + " NULLS LAST";
            case "updated_at" -> " ORDER BY updated_at " + dir + " NULLS LAST";
            case "name" -> " ORDER BY name " + dir + " NULLS LAST";
            default -> " ORDER BY date_taken_ms " + dir + " NULLS LAST";
        };
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
                    (UUID) rs.getObject("user_id"),
                    (UUID) rs.getObject("device_id"),
                    rs.getString("media_store_id"),
                    rs.getString("name"),
                    rs.getString("mime_type"),
                    rs.getString("thumb_b64")
            );

    private static final RowMapper<PhotoAssetSearchResult> SEARCH_ROW_MAPPER = (rs, rn) -> {
        double distance = rs.getDouble("distance");
        double score = 1.0 - distance;
        return new PhotoAssetSearchResult(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("device_id"),
                rs.getString("media_store_id"),
                rs.getString("name"),
                rs.getString("bucket_id"),
                rs.getString("bucket_name"),
                boxedLong(rs, "date_taken_ms"),
                boxedLong(rs, "date_modified_sec"),
                boxedLong(rs, "size_bytes"),
                boxedInt(rs, "width"),
                boxedInt(rs, "height"),
                rs.getString("mime_type"),
                rs.getString("content_hash"),
                rs.getString("thumb_b64"),
                rs.getString("embedding_model"),
                boxedInt(rs, "embedding_dim"),
                distance,
                score
        );
    };

    private static final RowMapper<PhotoAssetDto> ASSET_ROW_MAPPER = (rs, rn) ->
            new PhotoAssetDto(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject("user_id"),
                    (UUID) rs.getObject("device_id"),
                    rs.getString("media_store_id"),
                    rs.getString("name"),
                    rs.getString("bucket_id"),
                    rs.getString("bucket_name"),
                    boxedLong(rs, "date_taken_ms"),
                    boxedLong(rs, "date_modified_sec"),
                    boxedLong(rs, "size_bytes"),
                    boxedInt(rs, "width"),
                    boxedInt(rs, "height"),
                    rs.getString("mime_type"),
                    rs.getString("content_hash"),
                    rs.getString("thumb_b64"),
                    rs.getString("embedding_model"),
                    boxedInt(rs, "embedding_dim"),
                    odt(rs.getTimestamp("indexed_at")),
                    odt(rs.getTimestamp("updated_at"))
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
