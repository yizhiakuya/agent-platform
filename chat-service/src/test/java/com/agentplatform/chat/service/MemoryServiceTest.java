package com.agentplatform.chat.service;

import com.agentplatform.api.chat.MemoryFactDto;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MemoryServiceTest {

    @Test
    void saveFactNormalizesKindAndContentBeforeInsert() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MemoryService service = new MemoryService(jdbc);
        UUID userId = UUID.randomUUID();

        service.saveFact(userId, " Preference ", "  concise updates  ", null, new float[] {0.1f, 0.2f});

        verify(jdbc).update(anyString(),
                any(UUID.class),
                eq(userId),
                eq("preference"),
                eq("concise updates"),
                eq(null),
                any(Timestamp.class));
        verify(jdbc).update(anyString(), any(UUID.class), eq("[0.1,0.2]"));
    }

    @Test
    void saveFactRejectsInvalidKindAndOversizedContentBeforeInsert() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MemoryService service = new MemoryService(jdbc);
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> service.saveFact(
                userId,
                "secret",
                "remember this",
                null,
                new float[] {0.1f}))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("kind must be one of");

        assertThatThrownBy(() -> service.saveFact(
                userId,
                "fact",
                "x".repeat(4_001),
                null,
                new float[] {0.1f}))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("max 4000 chars");

        verifyNoInteractions(jdbc);
    }

    @Test
    void listFactsKeepsWhitespaceBetweenWhereAndOrderBy() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MemoryService service = new MemoryService(jdbc);
        UUID userId = UUID.randomUUID();
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<MemoryFactDto>>any(),
                eq(userId), eq(true), eq(20)))
                .thenReturn(List.of());

        service.listFacts(userId, 20, true);

        verify(jdbc).query(org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("WHERE user_id = ?\n  AND (? OR is_curated = true)\nORDER BY")),
                org.mockito.ArgumentMatchers.<RowMapper<MemoryFactDto>>any(),
                eq(userId),
                eq(true),
                eq(20));
    }
}
