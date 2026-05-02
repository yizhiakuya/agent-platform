package com.agentplatform.chat.repository;

import com.agentplatform.chat.entity.MemoryFact;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MemoryFactRepository extends JpaRepository<MemoryFact, UUID> {
    List<MemoryFact> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
