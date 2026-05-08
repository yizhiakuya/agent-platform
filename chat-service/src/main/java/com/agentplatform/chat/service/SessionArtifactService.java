package com.agentplatform.chat.service;

import com.agentplatform.api.chat.SessionArtifactDto;
import com.agentplatform.api.chat.UpsertSessionArtifactRequest;
import com.agentplatform.chat.entity.SessionArtifact;
import com.agentplatform.chat.repository.SessionArtifactRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class SessionArtifactService {

    private static final Logger log = LoggerFactory.getLogger(SessionArtifactService.class);
    private static final int MAX_LIMIT = 50;

    private final SessionArtifactRepository artifacts;
    private final SessionService sessionService;
    private final PiiSanitizer sanitizer;
    private final ObjectMapper mapper;

    public SessionArtifactService(SessionArtifactRepository artifacts,
                                  SessionService sessionService,
                                  PiiSanitizer sanitizer,
                                  ObjectMapper mapper) {
        this.artifacts = artifacts;
        this.sessionService = sessionService;
        this.sanitizer = sanitizer;
        this.mapper = mapper;
    }

    @Transactional
    public SessionArtifactDto upsert(UpsertSessionArtifactRequest req) {
        if (req.sessionId() == null || req.userId() == null
                || isBlank(req.artifactType()) || isBlank(req.artifactKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "sessionId, userId, artifactType and artifactKey are required");
        }
        sessionService.requireOwned(req.sessionId(), req.userId());

        SessionArtifact a = artifacts
                .findBySessionIdAndArtifactTypeAndArtifactKey(
                        req.sessionId(), req.artifactType(), req.artifactKey())
                .orElseGet(SessionArtifact::new);
        a.setSessionId(req.sessionId());
        a.setUserId(req.userId());
        a.setMessageId(req.messageId());
        a.setArtifactType(req.artifactType());
        a.setArtifactKey(req.artifactKey());
        a.setTitle(sanitizer.sanitizeContent(req.title()));
        a.setSummary(sanitizer.sanitizeContent(req.summary()));
        a.setMetadata(sanitizer.sanitizeMetadata(req.metadata()));
        a.setUpdatedAt(OffsetDateTime.now());
        return toDto(artifacts.save(a));
    }

    @Transactional(readOnly = true)
    public List<SessionArtifactDto> listRecent(UUID sessionId, UUID userId, int limit) {
        sessionService.requireOwned(sessionId, userId);
        int cap = Math.max(1, Math.min(limit <= 0 ? 12 : limit, MAX_LIMIT));
        return artifacts.findBySessionIdOrderByUpdatedAtDescCreatedAtDesc(sessionId, PageRequest.of(0, cap))
                .stream()
                .map(this::toDto)
                .toList();
    }

    private SessionArtifactDto toDto(SessionArtifact a) {
        JsonNode metadata = null;
        if (a.getMetadata() != null && !a.getMetadata().isBlank()) {
            try {
                metadata = mapper.readTree(a.getMetadata());
            } catch (Exception e) {
                log.debug("Failed to parse artifact metadata JSON for {}: {}", a.getId(), e.getMessage());
            }
        }
        return new SessionArtifactDto(
                a.getId(),
                a.getSessionId(),
                a.getUserId(),
                a.getMessageId(),
                a.getArtifactType(),
                a.getArtifactKey(),
                a.getTitle(),
                a.getSummary(),
                metadata,
                a.getCreatedAt(),
                a.getUpdatedAt());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
