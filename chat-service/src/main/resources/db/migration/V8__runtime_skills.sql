CREATE TABLE runtime_skills (
    id           UUID PRIMARY KEY,
    user_id      UUID        NOT NULL,
    name         VARCHAR(96) NOT NULL,
    description  TEXT        NOT NULL,
    body         TEXT        NOT NULL,
    enabled      BOOLEAN     NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_runtime_skills_user_name UNIQUE (user_id, name)
);

CREATE INDEX idx_runtime_skills_user_enabled
    ON runtime_skills (user_id, enabled, updated_at DESC);
