package com.agentplatform.chat.service;

import com.agentplatform.chat.entity.Session;
import com.agentplatform.chat.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionServiceTest {

    @Test
    void listByUserOnlyReturnsActiveSessions() {
        SessionRepository repository = mock(SessionRepository.class);
        SessionService service = new SessionService(repository);
        UUID userId = UUID.randomUUID();
        Session session = session(userId);
        when(repository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId))
                .thenReturn(List.of(session));

        assertThat(service.listByUser(userId)).hasSize(1);

        verify(repository).findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId);
    }

    @Test
    void deleteMarksSessionDeletedWithoutRemovingRow() {
        SessionRepository repository = mock(SessionRepository.class);
        SessionService service = new SessionService(repository);
        UUID userId = UUID.randomUUID();
        Session session = session(userId);
        when(repository.findById(session.getId())).thenReturn(Optional.of(session));
        when(repository.save(any(Session.class))).thenAnswer(inv -> inv.getArgument(0));

        service.delete(session.getId(), userId);

        assertThat(session.getDeletedAt()).isNotNull();
        verify(repository).save(session);
    }

    @Test
    void requireOwnedTreatsDeletedSessionAsNotFound() {
        SessionRepository repository = mock(SessionRepository.class);
        SessionService service = new SessionService(repository);
        UUID userId = UUID.randomUUID();
        Session session = session(userId);
        session.setDeletedAt(OffsetDateTime.now());
        when(repository.findById(session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.get(session.getId(), userId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
    }

    @Test
    void touchLastMessageDoesNotReviveDeletedSession() {
        SessionRepository repository = mock(SessionRepository.class);
        SessionService service = new SessionService(repository);
        Session session = session(UUID.randomUUID());
        session.setDeletedAt(OffsetDateTime.now());
        when(repository.findById(session.getId())).thenReturn(Optional.of(session));

        service.touchLastMessage(session.getId());

        assertThat(session.getLastMessageAt()).isNull();
    }

    private static Session session(UUID userId) {
        Session session = new Session();
        session.setId(UUID.randomUUID());
        session.setUserId(userId);
        session.setTitle("Chat");
        return session;
    }
}
