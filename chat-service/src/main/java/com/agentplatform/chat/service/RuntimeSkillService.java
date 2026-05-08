package com.agentplatform.chat.service;

import com.agentplatform.api.chat.RuntimeSkillDto;
import com.agentplatform.api.chat.UpsertRuntimeSkillRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class RuntimeSkillService {

    private static final int MAX_BODY_CHARS = 20_000;
    private static final int MAX_DESCRIPTION_CHARS = 500;
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    private final JdbcTemplate jdbc;

    public RuntimeSkillService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public RuntimeSkillDto upsert(UpsertRuntimeSkillRequest req) {
        if (req == null || req.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId required");
        }
        String name = normalizeName(req.name());
        String description = normalize(req.description(), MAX_DESCRIPTION_CHARS, "description");
        String body = normalize(req.body(), MAX_BODY_CHARS, "body");
        boolean enabled = req.enabled() == null || req.enabled();
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        RuntimeSkillDto existing = get(req.userId(), name);
        UUID finalId = existing == null ? id : existing.id();
        Timestamp nowTs = Timestamp.from(now.toInstant());
        jdbc.update("""
                INSERT INTO runtime_skills (id, user_id, name, description, body, enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, name) DO UPDATE
                SET description = EXCLUDED.description,
                    body = EXCLUDED.body,
                    enabled = EXCLUDED.enabled,
                    updated_at = EXCLUDED.updated_at
                """,
                finalId, req.userId(), name, description, body, enabled, nowTs, nowTs);
        RuntimeSkillDto saved = get(req.userId(), name);
        if (saved == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "skill save failed");
        }
        return saved;
    }

    public List<RuntimeSkillDto> list(UUID userId, boolean includeDisabled) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId required");
        }
        if (includeDisabled) {
            return jdbc.query("""
                    SELECT id, user_id, name, description, body, enabled, created_at, updated_at
                    FROM runtime_skills
                    WHERE user_id = ?
                    ORDER BY enabled DESC, updated_at DESC, name ASC
                    """,
                    ROW_MAPPER,
                    userId);
        }
        return jdbc.query("""
                SELECT id, user_id, name, description, body, enabled, created_at, updated_at
                FROM runtime_skills
                WHERE user_id = ? AND enabled = true
                ORDER BY updated_at DESC, name ASC
                """,
                ROW_MAPPER,
                userId);
    }

    public RuntimeSkillDto get(UUID userId, String name) {
        if (userId == null || name == null || name.isBlank()) return null;
        String normalized = normalizeName(name);
        List<RuntimeSkillDto> rows = jdbc.query("""
                SELECT id, user_id, name, description, body, enabled, created_at, updated_at
                FROM runtime_skills
                WHERE user_id = ? AND name = ?
                LIMIT 1
                """,
                ROW_MAPPER,
                userId, normalized);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional
    public boolean delete(UUID userId, String name) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId required");
        }
        String normalized = normalizeName(name);
        return jdbc.update("DELETE FROM runtime_skills WHERE user_id = ? AND name = ?", userId, normalized) > 0;
    }

    private static String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name required");
        }
        String normalized = value.trim();
        if (!NAME_PATTERN.matcher(normalized).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "name must match [a-zA-Z0-9_-]{1,64}");
        }
        return normalized;
    }

    private static String normalize(String value, int maxChars, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " required");
        }
        String out = value.trim();
        if (out.length() > maxChars) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " too long; max " + maxChars + " chars");
        }
        return out;
    }

    private static final RowMapper<RuntimeSkillDto> ROW_MAPPER = (rs, rn) -> {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return new RuntimeSkillDto(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("user_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("body"),
                rs.getBoolean("enabled"),
                created == null ? null : OffsetDateTime.ofInstant(created.toInstant(), ZoneOffset.UTC),
                updated == null ? null : OffsetDateTime.ofInstant(updated.toInstant(), ZoneOffset.UTC)
        );
    };
}
