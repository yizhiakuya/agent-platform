package com.agentplatform.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Revoked token-id (jti). On verify(), look up the principal's jti here to reject
 * tokens that have been logged out / device-removed / etc. Rows older than the
 * token's natural expiration can safely be pruned.
 */
@Entity
@Table(name = "revoked_jtis")
public class RevokedJti {

    @Id
    @Column(length = 64, nullable = false)
    private String jti;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at", nullable = false, updatable = false)
    private OffsetDateTime revokedAt = OffsetDateTime.now();

    public String getJti() { return jti; }
    public void setJti(String jti) { this.jti = jti; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(OffsetDateTime revokedAt) { this.revokedAt = revokedAt; }
}
