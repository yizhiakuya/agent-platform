package com.agentplatform.chat.service;

import com.agentplatform.api.chat.SessionDto;
import com.agentplatform.chat.entity.Session;
import com.agentplatform.chat.repository.SessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class SessionService {

    private final SessionRepository sessions;

    public SessionService(SessionRepository sessions) {
        this.sessions = sessions;
    }

    @Transactional
    public SessionDto create(UUID userId, String title) {
        Session s = new Session();
        s.setUserId(userId);
        s.setTitle(title);
        sessions.save(s);
        return toDto(s);
    }

    @Transactional(readOnly = true)
    public List<SessionDto> listByUser(UUID userId) {
        return sessions.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public SessionDto get(UUID sessionId, UUID userId) {
        return toDto(requireOwned(sessionId, userId));
    }

    @Transactional
    public void delete(UUID sessionId, UUID userId) {
        sessions.delete(requireOwned(sessionId, userId));
    }

    @Transactional
    public void touchLastMessage(UUID sessionId) {
        sessions.findById(sessionId).ifPresent(s -> {
            s.setLastMessageAt(OffsetDateTime.now());
            sessions.save(s);
        });
    }

    Session requireOwned(UUID sessionId, UUID userId) {
        Session s = sessions.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        if (!s.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Session belongs to another user");
        }
        return s;
    }

    private SessionDto toDto(Session s) {
        return new SessionDto(s.getId(), s.getUserId(), s.getTitle(), s.getCreatedAt(), s.getLastMessageAt());
    }
}
