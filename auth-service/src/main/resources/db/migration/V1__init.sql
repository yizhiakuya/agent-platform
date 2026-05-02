-- auth-service initial schema (Flyway V1)
-- The 'auth' schema is owned by the agent_auth role and pre-created by
-- infra/postgres/init/01-init.sh.

CREATE TABLE users (
    id            UUID PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE devices (
    id           UUID PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name         VARCHAR(64)  NOT NULL,
    model        VARCHAR(128),
    os_version   VARCHAR(32),
    last_seen_at TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_devices_user_id ON devices(user_id);
CREATE INDEX idx_devices_active  ON devices(user_id) WHERE revoked_at IS NULL;

CREATE TABLE enrollments (
    token_hash VARCHAR(64) PRIMARY KEY,         -- SHA-256 hex (always 64 chars)
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_enrollments_user_id    ON enrollments(user_id);
CREATE INDEX idx_enrollments_expires_at ON enrollments(expires_at);

CREATE TABLE revoked_jtis (
    jti        VARCHAR(64) PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_revoked_jtis_expires_at ON revoked_jtis(expires_at);
