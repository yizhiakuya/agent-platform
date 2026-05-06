package com.agentplatform.chat.repository;

import com.agentplatform.chat.entity.SessionContextSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SessionContextSummaryRepository extends JpaRepository<SessionContextSummary, UUID> {
    Optional<SessionContextSummary> findBySessionId(UUID sessionId);
}
