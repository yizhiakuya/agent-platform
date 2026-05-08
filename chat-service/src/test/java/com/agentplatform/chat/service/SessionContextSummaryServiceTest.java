package com.agentplatform.chat.service;

import com.agentplatform.api.chat.SessionContextSummaryDto;
import com.agentplatform.api.chat.UpsertSessionContextSummaryRequest;
import com.agentplatform.chat.config.ChatProperties;
import com.agentplatform.chat.entity.SessionContextSummary;
import com.agentplatform.chat.repository.SessionContextSummaryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionContextSummaryServiceTest {

    @Test
    void upsertReusesExistingSummaryRowForSession() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        SessionContextSummary existing = new SessionContextSummary();
        existing.setId(rowId);
        existing.setSessionId(sessionId);
        existing.setUserId(userId);
        existing.setSummary("old");

        SessionContextSummaryRepository summaries = mock(SessionContextSummaryRepository.class);
        when(summaries.findBySessionId(sessionId)).thenReturn(Optional.of(existing));
        when(summaries.save(any(SessionContextSummary.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SessionService sessionService = mock(SessionService.class);
        PiiSanitizer sanitizer = new PiiSanitizer(
                new ChatProperties(
                        new ChatProperties.Jwt("secret", "issuer"),
                        new ChatProperties.Chat(64_000, 16_000)),
                new ObjectMapper());
        SessionContextSummaryService service =
                new SessionContextSummaryService(summaries, sessionService, sanitizer);

        UUID coveredUntil = UUID.randomUUID();
        SessionContextSummaryDto dto = service.upsert(new UpsertSessionContextSummaryRequest(
                sessionId,
                userId,
                coveredUntil,
                18,
                "new summary",
                42));

        ArgumentCaptor<SessionContextSummary> captor = ArgumentCaptor.forClass(SessionContextSummary.class);
        verify(summaries).save(captor.capture());
        assertThat(dto.id()).isEqualTo(rowId);
        assertThat(dto.summary()).isEqualTo("new summary");
        assertThat(dto.coveredMessageCount()).isEqualTo(18);
        assertThat(dto.tokenEstimate()).isEqualTo(42);
        assertThat(captor.getValue().getCoveredUntilMessageId()).isEqualTo(coveredUntil);
    }
}
