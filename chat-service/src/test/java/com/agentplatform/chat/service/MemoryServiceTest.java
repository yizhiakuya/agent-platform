package com.agentplatform.chat.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
}
