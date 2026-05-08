package com.agentplatform.chat.service;

import com.agentplatform.api.chat.MessageDto;
import com.agentplatform.api.chat.WriteMessageRequest;
import com.agentplatform.chat.entity.Message;
import com.agentplatform.chat.entity.Session;
import com.agentplatform.chat.repository.MessageRepository;
import com.agentplatform.chat.repository.SessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    private final MessageRepository messages;
    private final SessionRepository sessions;
    private final SessionService sessionService;
    private final PiiSanitizer sanitizer;
    private final ObjectMapper mapper;

    public MessageService(MessageRepository messages,
                          SessionRepository sessions,
                          SessionService sessionService,
                          PiiSanitizer sanitizer,
                          ObjectMapper mapper) {
        this.messages = messages;
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.sanitizer = sanitizer;
        this.mapper = mapper;
    }

    @Transactional
    public MessageDto write(WriteMessageRequest req) {
        if (req.sessionId() == null || req.userId() == null || req.role() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "sessionId, userId and role are required");
        }
        Session s = sessions.findById(req.sessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        if (s.getDeletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
        }
        if (!s.getUserId().equals(req.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Session belongs to another user");
        }

        Message m = new Message();
        m.setSessionId(req.sessionId());
        m.setRole(req.role());
        m.setContent(sanitizer.sanitizeContent(req.content() == null ? "" : req.content()));
        m.setMetadata(sanitizer.sanitizeMetadata(req.metadata()));
        messages.save(m);

        sessionService.touchLastMessage(req.sessionId());
        return toDto(m);
    }

    @Transactional(readOnly = true)
    public List<MessageDto> listBySession(UUID sessionId, UUID userId) {
        sessionService.requireOwned(sessionId, userId);
        return messages.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(this::toDto)
                .toList();
    }

    private MessageDto toDto(Message m) {
        JsonNode metadata = null;
        if (m.getMetadata() != null && !m.getMetadata().isBlank()) {
            try {
                metadata = mapper.readTree(m.getMetadata());
            } catch (Exception e) {
                log.debug("Failed to parse metadata JSON for message {}: {}", m.getId(), e.getMessage());
            }
        }
        return new MessageDto(m.getId(), m.getSessionId(), m.getRole(),
                m.getContent(), metadata, m.getCreatedAt());
    }
}
