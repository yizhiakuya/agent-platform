package com.agentplatform.chat.service;

import com.agentplatform.api.chat.SessionContextSummaryDto;
import com.agentplatform.api.chat.UpsertSessionContextSummaryRequest;
import com.agentplatform.chat.entity.SessionContextSummary;
import com.agentplatform.chat.repository.SessionContextSummaryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class SessionContextSummaryService {

    private static final int MAX_SUMMARY_CHARS = 24_000;

    private final SessionContextSummaryRepository summaries;
    private final SessionService sessionService;
    private final PiiSanitizer sanitizer;

    public SessionContextSummaryService(SessionContextSummaryRepository summaries,
                                        SessionService sessionService,
                                        PiiSanitizer sanitizer) {
        this.summaries = summaries;
        this.sessionService = sessionService;
        this.sanitizer = sanitizer;
    }

    @Transactional(readOnly = true)
    public SessionContextSummaryDto get(UUID sessionId, UUID userId) {
        sessionService.requireOwned(sessionId, userId);
        return summaries.findBySessionId(sessionId)
                .map(this::toDto)
                .orElse(null);
    }

    @Transactional
    public SessionContextSummaryDto upsert(UpsertSessionContextSummaryRequest req) {
        if (req.sessionId() == null || req.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId and userId are required");
        }
        if (req.summary() == null || req.summary().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "summary is required");
        }
        sessionService.requireOwned(req.sessionId(), req.userId());

        SessionContextSummary row = summaries.findBySessionId(req.sessionId())
                .orElseGet(SessionContextSummary::new);
        row.setSessionId(req.sessionId());
        row.setUserId(req.userId());
        row.setCoveredUntilMessageId(req.coveredUntilMessageId());
        row.setCoveredMessageCount(Math.max(0, req.coveredMessageCount()));
        row.setSummary(trim(sanitizer.sanitizeContent(req.summary()), MAX_SUMMARY_CHARS));
        row.setTokenEstimate(Math.max(0, req.tokenEstimate()));
        row.setUpdatedAt(OffsetDateTime.now());
        return toDto(summaries.save(row));
    }

    private SessionContextSummaryDto toDto(SessionContextSummary row) {
        return new SessionContextSummaryDto(
                row.getId(),
                row.getSessionId(),
                row.getUserId(),
                row.getCoveredUntilMessageId(),
                row.getCoveredMessageCount(),
                row.getSummary(),
                row.getTokenEstimate(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    private static String trim(String value, int maxChars) {
        String out = value == null ? "" : value.trim();
        if (out.length() <= maxChars) return out;
        return out.substring(0, maxChars);
    }
}
