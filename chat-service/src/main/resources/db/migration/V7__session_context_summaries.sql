-- Rolling summaries for older conversation turns.
--
-- The agent-service keeps the recent tail verbatim, while this table stores
-- a compact, session-scoped summary of earlier USER/ASSISTANT messages.

CREATE TABLE session_context_summaries (
    id                        UUID PRIMARY KEY,
    session_id                UUID        NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    user_id                   UUID        NOT NULL,
    covered_until_message_id  UUID        NULL REFERENCES messages(id) ON DELETE SET NULL,
    covered_message_count     INTEGER     NOT NULL DEFAULT 0,
    summary                   TEXT        NOT NULL,
    token_estimate            INTEGER     NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (session_id)
);

CREATE INDEX idx_session_context_summaries_user_updated
    ON session_context_summaries(user_id, updated_at DESC);
