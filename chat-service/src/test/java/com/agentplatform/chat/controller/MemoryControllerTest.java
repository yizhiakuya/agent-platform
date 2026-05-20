package com.agentplatform.chat.controller;

import com.agentplatform.api.chat.MemoryFactDto;
import com.agentplatform.chat.service.MemoryService;
import com.agentplatform.security.Principal;
import com.agentplatform.security.PrincipalContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryControllerTest {

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void listUsesAuthenticatedUser() {
        MemoryService service = mock(MemoryService.class);
        UUID userId = UUID.randomUUID();
        MemoryFactDto row = new MemoryFactDto(
                UUID.randomUUID(),
                userId,
                "preference",
                "用中文回答。",
                null,
                OffsetDateTime.now(),
                true,
                2,
                OffsetDateTime.now());
        when(service.listFacts(userId, 50, true)).thenReturn(List.of(row));
        PrincipalContext.set(new Principal(Principal.TYPE_USER, userId.toString(), userId.toString(), "jti"));

        List<MemoryFactDto> rows = new MemoryController(service).list(50, true);

        assertThat(rows).containsExactly(row);
        verify(service).listFacts(userId, 50, true);
    }

    @Test
    void deleteUsesAuthenticatedUser() {
        MemoryService service = mock(MemoryService.class);
        UUID userId = UUID.randomUUID();
        UUID memoryId = UUID.randomUUID();
        PrincipalContext.set(new Principal(Principal.TYPE_USER, userId.toString(), userId.toString(), "jti"));

        new MemoryController(service).delete(memoryId);

        verify(service).deleteFact(userId, memoryId);
    }
}
