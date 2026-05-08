package com.agentplatform.chat.service;

import com.agentplatform.api.chat.RuntimeSkillDto;
import com.agentplatform.api.chat.UpsertRuntimeSkillRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class RuntimeSkillServiceTest {

    private static RowMapper<RuntimeSkillDto> anyRuntimeSkillRowMapper() {
        return any(RowMapper.class);
    }

    @Test
    void upsertNormalizesNameAndReusesExistingRowId() {
        UUID userId = UUID.randomUUID();
        UUID existingId = UUID.randomUUID();
        RuntimeSkillDto existing = skill(existingId, userId, "deploy-flow", "old", "old body", true);
        RuntimeSkillDto saved = skill(existingId, userId, "deploy-flow", "new description", "new body", false);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), anyRuntimeSkillRowMapper(), eq(userId), eq("deploy-flow")))
                .thenReturn(List.of(existing), List.of(saved));
        RuntimeSkillService service = new RuntimeSkillService(jdbc);

        RuntimeSkillDto result = service.upsert(new UpsertRuntimeSkillRequest(
                userId,
                " deploy-flow ",
                " new description ",
                " new body ",
                false));

        assertThat(result).isEqualTo(saved);
        verify(jdbc).update(anyString(),
                eq(existingId),
                eq(userId),
                eq("deploy-flow"),
                eq("new description"),
                eq("new body"),
                eq(false),
                any(Timestamp.class),
                any(Timestamp.class));
    }

    @Test
    void listOmitsDisabledRowsByDefault() {
        UUID userId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RuntimeSkillDto enabled = skill(UUID.randomUUID(), userId, "runbook", "desc", "body", true);
        when(jdbc.query(anyString(), anyRuntimeSkillRowMapper(), eq(userId))).thenReturn(List.of(enabled));
        RuntimeSkillService service = new RuntimeSkillService(jdbc);

        List<RuntimeSkillDto> rows = service.list(userId, false);

        assertThat(rows).containsExactly(enabled);
        verify(jdbc).query(org.mockito.ArgumentMatchers.contains("enabled = true"),
                anyRuntimeSkillRowMapper(),
                eq(userId));
    }

    @Test
    void deleteUsesNormalizedNameAndUserScope() {
        UUID userId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), eq(userId), eq("deploy-flow"))).thenReturn(1);
        RuntimeSkillService service = new RuntimeSkillService(jdbc);

        assertThat(service.delete(userId, " deploy-flow ")).isTrue();

        verify(jdbc).update(anyString(), eq(userId), eq("deploy-flow"));
    }

    @Test
    void rejectsInvalidNameAndOversizedBody() {
        RuntimeSkillService service = new RuntimeSkillService(mock(JdbcTemplate.class));
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> service.upsert(new UpsertRuntimeSkillRequest(
                userId,
                "bad name!",
                "description",
                "body",
                true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("[a-zA-Z0-9_-]");

        assertThatThrownBy(() -> service.upsert(new UpsertRuntimeSkillRequest(
                userId,
                "good_name",
                "description",
                "x".repeat(20_001),
                true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("max 20000 chars");
    }

    private static RuntimeSkillDto skill(UUID id,
                                         UUID userId,
                                         String name,
                                         String description,
                                         String body,
                                         boolean enabled) {
        OffsetDateTime now = OffsetDateTime.now();
        return new RuntimeSkillDto(id, userId, name, description, body, enabled, now, now);
    }
}
