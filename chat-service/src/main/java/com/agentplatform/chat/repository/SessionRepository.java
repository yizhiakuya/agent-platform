package com.agentplatform.chat.repository;

import com.agentplatform.chat.entity.Session;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<Session, UUID> {
    List<Session> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID userId);
}
