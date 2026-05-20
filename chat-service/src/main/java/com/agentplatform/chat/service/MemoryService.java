package com.agentplatform.chat.service;

import com.agentplatform.api.chat.MemoryFactDto;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * Long-term memory persistence + retrieval.
 *
 * <p>memory_facts is JPA-mapped (see {@link com.agentplatform.chat.entity.MemoryFact}),
 * but everything that touches the {@code vector(1536)} column goes through
 * {@code JdbcTemplate} with a {@code ?::public.vector} cast — Hibernate has no native
 * type for pgvector and we don't want to drag in pgvector-java just to wrap a
 * String. Insert is two statements in one transaction, query is a single JOIN
 * sorted by cosine distance ({@code <=>}, smaller = closer).
 *
 * <p>P1.5: a "curated" tier was added on top. {@link #queryFacts} runs in two stages
 * (curated first, then raw to fill the remainder) so high-confidence facts always
 * show up at the top of RAG, and {@link #promoteHotFacts} elevates frequently-hit
 * raw facts into that tier based on {@code access_count}.
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private static final int MAX_TOP_K = 50;
    private static final int MAX_CONTENT_CHARS = 4_000;

    private final JdbcTemplate jdbc;

    public MemoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert a fact + its embedding. Returns the generated fact id.
     */
    @Transactional
    public UUID saveFact(UUID userId,
                         String kind,
                         String content,
                         UUID sourceMessageId,
                         float[] embedding) {
        if (userId == null || kind == null || kind.isBlank()
                || content == null || content.isBlank()
                || embedding == null || embedding.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "userId, kind, content and embedding are required");
        }
        String normalizedKind = normalizeKind(kind);
        String normalizedContent = content.trim();
        if (normalizedContent.length() > MAX_CONTENT_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "content too long; max " + MAX_CONTENT_CHARS + " chars");
        }

        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        jdbc.update("""
                INSERT INTO memory_facts (id, user_id, kind, content, source_message_id,
                                          created_at, is_curated, curated_at)
                VALUES (?, ?, ?, ?, ?, ?, false, NULL)
                """,
                id, userId, normalizedKind, normalizedContent, sourceMessageId, Timestamp.from(now.toInstant()));

        jdbc.update("""
                INSERT INTO memory_embeddings (fact_id, embedding)
                VALUES (?, ?::public.vector)
                """,
                id, toVectorLiteral(embedding));

        log.debug("Saved memory fact {} for user {} (kind={}, dim={})",
                id, userId, normalizedKind, embedding.length);
        return id;
    }

    /**
     * Insert a manually curated memory fact. Agent-facing memory tools use this
     * when the model intentionally saves a durable rule/preference/fact.
     */
    @Transactional
    public UUID saveCuratedFact(UUID userId,
                                String kind,
                                String content,
                                UUID sourceMessageId,
                                float[] embedding) {
        UUID id = saveFact(userId, kind, content, sourceMessageId, embedding);
        jdbc.update("UPDATE memory_facts SET is_curated = true, curated_at = ? WHERE id = ?",
                Timestamp.from(OffsetDateTime.now(ZoneOffset.UTC).toInstant()), id);
        return id;
    }

    public List<MemoryFactDto> listFacts(UUID userId, int limit, boolean includeRaw) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        int cap = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100));
        String where = includeRaw ? "WHERE user_id = ?\n" : "WHERE user_id = ? AND is_curated = true\n";
        return jdbc.query("""
                SELECT id, user_id, kind, content, source_message_id,
                       created_at, is_curated, access_count, curated_at
                FROM memory_facts
                """ + where + """
                ORDER BY is_curated DESC, created_at DESC
                LIMIT ?
                """,
                FACT_ROW_MAPPER,
                userId, cap);
    }

    @Transactional
    public boolean deleteFact(UUID userId, UUID factId) {
        if (userId == null || factId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId and factId are required");
        }
        return jdbc.update("DELETE FROM memory_facts WHERE user_id = ? AND id = ?", userId, factId) > 0;
    }

    /**
     * Top-K nearest facts for {@code userId} under cosine distance, with the
     * curated tier prioritised.
     *
     * <p>Stage 1 fetches up to {@code max(1, topK / 2)} hits restricted to
     * {@code is_curated = true}. Stage 2 fills the remainder from the full set,
     * de-duplicating against stage 1. The final list keeps stage-1 entries
     * first so the formatter can render them in the high-confidence section.
     *
     * <p>As a side-effect, {@code access_count} is bumped for every returned
     * fact id (same transaction) — that's the signal {@link #promoteHotFacts}
     * later uses to choose what to elevate.
     */
    @Transactional
    public List<MemoryFactDto> queryFacts(UUID userId, float[] queryEmbedding, int topK) {
        if (userId == null || queryEmbedding == null || queryEmbedding.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "userId and queryEmbedding are required");
        }
        int k = Math.max(1, Math.min(topK <= 0 ? 5 : topK, MAX_TOP_K));
        int kCurated = Math.max(1, k / 2);
        String vec = toVectorLiteral(queryEmbedding);

        // Stage 1: curated only.
        List<MemoryFactDto> curated = jdbc.query("""
                SELECT mf.id, mf.user_id, mf.kind, mf.content,
                       mf.source_message_id, mf.created_at,
                       mf.is_curated, mf.access_count, mf.curated_at
                FROM memory_facts mf
                JOIN memory_embeddings me ON me.fact_id = mf.id
                WHERE mf.user_id = ? AND mf.is_curated = true
                ORDER BY me.embedding OPERATOR(public.<=>) ?::public.vector
                LIMIT ?
                """,
                FACT_ROW_MAPPER,
                userId, vec, kCurated);

        // Order-preserving merge: curated first, then raw fillers.
        LinkedHashMap<UUID, MemoryFactDto> merged = new LinkedHashMap<>();
        for (MemoryFactDto f : curated) merged.put(f.id(), f);

        int remaining = k - merged.size();
        if (remaining > 0) {
            // Stage 2: full set, fetch a few extra so we still return `remaining`
            // rows after filtering out anything already in stage-1.
            int fetch = remaining + curated.size();
            List<MemoryFactDto> all = jdbc.query("""
                    SELECT mf.id, mf.user_id, mf.kind, mf.content,
                           mf.source_message_id, mf.created_at,
                           mf.is_curated, mf.access_count, mf.curated_at
                    FROM memory_facts mf
                    JOIN memory_embeddings me ON me.fact_id = mf.id
                    WHERE mf.user_id = ?
                    ORDER BY me.embedding OPERATOR(public.<=>) ?::public.vector
                    LIMIT ?
                    """,
                    FACT_ROW_MAPPER,
                    userId, vec, fetch);
            for (MemoryFactDto f : all) {
                if (merged.size() >= k) break;
                merged.putIfAbsent(f.id(), f);
            }
        }

        List<MemoryFactDto> out = new ArrayList<>(merged.values());

        // Bump access_count on hit. Single batched UPDATE; stays within the same
        // @Transactional boundary so a query+bump is atomic.
        if (!out.isEmpty()) {
            List<UUID> ids = new ArrayList<>(out.size());
            for (MemoryFactDto f : out) ids.add(f.id());
            String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
            jdbc.update(
                    "UPDATE memory_facts SET access_count = access_count + 1 WHERE id IN (" + placeholders + ")",
                    ids.toArray());
        }
        return out;
    }

    /**
     * Promote up to {@code maxToPromote} of {@code userId}'s raw facts whose
     * {@code access_count} has reached {@code minAccessCount} into the curated
     * tier. Highest access_count wins ties broken by created_at desc.
     *
     * <p>Postgres can't {@code UPDATE ... LIMIT}, so we pick ids in a
     * {@code SELECT ... LIMIT} subquery and update by id-set.
     *
     * @return number of rows promoted.
     */
    @Transactional
    public int promoteHotFacts(UUID userId, int minAccessCount, int maxToPromote) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        int min = Math.max(1, minAccessCount);
        int cap = Math.max(1, Math.min(maxToPromote <= 0 ? 20 : maxToPromote, 200));

        List<UUID> ids = jdbc.query("""
                SELECT id FROM memory_facts
                WHERE user_id = ? AND is_curated = false AND access_count >= ?
                ORDER BY access_count DESC, created_at DESC
                LIMIT ?
                """,
                (rs, rn) -> (UUID) rs.getObject("id"),
                userId, min, cap);

        if (ids.isEmpty()) return 0;

        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Object[] args = new Object[ids.size() + 1];
        args[0] = Timestamp.from(OffsetDateTime.now(ZoneOffset.UTC).toInstant());
        for (int i = 0; i < ids.size(); i++) args[i + 1] = ids.get(i);

        int updated = jdbc.update(
                "UPDATE memory_facts SET is_curated = true, curated_at = ? WHERE id IN ("
                        + placeholders + ")",
                args);
        log.info("Promoted {} fact(s) to curated for user {} (min_access={}, cap={})",
                updated, userId, min, cap);
        return updated;
    }

    /**
     * Demote curated facts older than {@code daysOld} back to raw.
     *
     * <p>v0 implementation: pure age-based demotion. TODO P2: combine with a
     * "no recall hits in N days" signal so we don't demote curated facts that
     * are still actively being retrieved.
     */
    @Transactional
    public void demoteStaleCurated(UUID userId, int daysOld) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        int days = Math.max(1, daysOld);
        // TODO: factor in last-recall-hit timestamp once we track it; for now,
        // age of the curated_at field alone is the demotion signal.
        int demoted = jdbc.update("""
                UPDATE memory_facts
                SET is_curated = false, curated_at = NULL
                WHERE user_id = ?
                  AND is_curated = true
                  AND curated_at IS NOT NULL
                  AND curated_at < now() - make_interval(days => ?)
                """,
                userId, days);
        if (demoted > 0) {
            log.info("Demoted {} stale curated fact(s) for user {} (older than {} days)",
                    demoted, userId, days);
        }
    }

    private static final RowMapper<MemoryFactDto> FACT_ROW_MAPPER = (rs, rn) -> {
        Timestamp ts = rs.getTimestamp("created_at");
        OffsetDateTime created = ts == null ? null
                : OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC);
        Timestamp cts = rs.getTimestamp("curated_at");
        OffsetDateTime curatedAt = cts == null ? null
                : OffsetDateTime.ofInstant(cts.toInstant(), ZoneOffset.UTC);
        UUID src = (UUID) rs.getObject("source_message_id");
        return new MemoryFactDto(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("user_id"),
                rs.getString("kind"),
                rs.getString("content"),
                src,
                created,
                rs.getBoolean("is_curated"),
                rs.getInt("access_count"),
                curatedAt
        );
    };

    /**
     * Format a float[] as the pgvector text literal {@code [0.1,0.2,...]}.
     * Locale-independent (Float.toString), no scientific-notation surprises
     * for the values we'll see from OpenAI embeddings.
     */
    static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(Float.toString(v[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String normalizeKind(String kind) {
        String normalized = kind.trim().toLowerCase();
        return switch (normalized) {
            case "fact", "preference", "rule", "lesson" -> normalized;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "kind must be one of fact, preference, rule, lesson");
        };
    }
}
