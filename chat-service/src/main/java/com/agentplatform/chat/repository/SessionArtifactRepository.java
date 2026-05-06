package com.agentplatform.chat.repository;

import com.agentplatform.chat.entity.SessionArtifact;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionArtifactRepository extends JpaRepository<SessionArtifact, UUID> {
    Optional<SessionArtifact> findBySessionIdAndArtifactTypeAndArtifactKey(
            UUID sessionId, String artifactType, String artifactKey);

    List<SessionArtifact> findBySessionIdOrderByUpdatedAtDescCreatedAtDesc(UUID sessionId, Pageable pageable);
}
