package com.agentplatform.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "session_context_summaries")
public class SessionContextSummary extends SessionScopedEntity {

    @Column(name = "covered_until_message_id")
    private UUID coveredUntilMessageId;

    @Column(name = "covered_message_count", nullable = false)
    private int coveredMessageCount;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(name = "token_estimate", nullable = false)
    private int tokenEstimate;

    public UUID getCoveredUntilMessageId() { return coveredUntilMessageId; }
    public void setCoveredUntilMessageId(UUID coveredUntilMessageId) { this.coveredUntilMessageId = coveredUntilMessageId; }
    public int getCoveredMessageCount() { return coveredMessageCount; }
    public void setCoveredMessageCount(int coveredMessageCount) { this.coveredMessageCount = coveredMessageCount; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public int getTokenEstimate() { return tokenEstimate; }
    public void setTokenEstimate(int tokenEstimate) { this.tokenEstimate = tokenEstimate; }
}
