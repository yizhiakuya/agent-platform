package com.agentplatform.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One extracted long-term memory fact about a user.
 *
 * <p>The accompanying embedding lives in a separate table {@code memory_embeddings}
 * (1:1 by {@code fact_id}); the pgvector column is intentionally NOT mapped via JPA
 * because Hibernate has no first-class support for the {@code vector(N)} type.
 * Embedding rows are inserted/queried via {@link com.agentplatform.chat.service.MemoryService}
 * using {@code JdbcTemplate} with a {@code ?::vector} cast.
 */
@Entity
@Table(name = "memory_facts")
public class MemoryFact {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 32)
    private String kind;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "source_message_id")
    private UUID sourceMessageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    /**
     * True once this fact has been promoted into the "curated" tier.
     * RAG retrieval prefers curated facts (see MemoryService#queryFacts).
     */
    @Column(name = "is_curated", nullable = false)
    private boolean isCurated = false;

    /**
     * Number of times this fact has been returned by a recall query.
     * Used by promoteHotFacts to elevate frequently-hit raw facts to curated.
     */
    @Column(name = "access_count", nullable = false)
    private int accessCount = 0;

    /** Set when promoted; null while still raw. */
    @Column(name = "curated_at")
    private OffsetDateTime curatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public UUID getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(UUID sourceMessageId) { this.sourceMessageId = sourceMessageId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public boolean isCurated() { return isCurated; }
    public void setCurated(boolean curated) { this.isCurated = curated; }
    public int getAccessCount() { return accessCount; }
    public void setAccessCount(int accessCount) { this.accessCount = accessCount; }
    public OffsetDateTime getCuratedAt() { return curatedAt; }
    public void setCuratedAt(OffsetDateTime curatedAt) { this.curatedAt = curatedAt; }
}
