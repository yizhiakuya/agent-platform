-- User-level preference document (one row per user). Free-form markdown that
-- gets injected into the LLM prompt by agent-service, analogous to a personal
-- CLAUDE.md. Lives in the auth schema alongside users; cross-service access
-- (agent-service) goes through the internal HTTP endpoint, never direct SQL.

CREATE TABLE user_preferences (
    user_id    UUID        PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    content    TEXT        NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
