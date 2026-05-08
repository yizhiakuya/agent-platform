-- Session-scoped working set for tool-produced objects.
--
-- This table stores lightweight references only: IDs, human summaries, and
-- bounded metadata. Original images/files remain on the device or in the tool
-- result metadata. The agent-service injects recent artifacts into the next
-- turn so phrases like "that image" can resolve without re-running search.

CREATE TABLE session_artifacts (
    id             UUID PRIMARY KEY,
    session_id     UUID        NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    user_id        UUID        NOT NULL,
    message_id     UUID        NULL REFERENCES messages(id) ON DELETE SET NULL,
    artifact_type  VARCHAR(64) NOT NULL,
    artifact_key   TEXT        NOT NULL,
    title          TEXT        NULL,
    summary        TEXT        NULL,
    metadata       TEXT        NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (session_id, artifact_type, artifact_key)
);

CREATE INDEX idx_session_artifacts_session_created
    ON session_artifacts(session_id, updated_at DESC, created_at DESC);
CREATE INDEX idx_session_artifacts_user_created
    ON session_artifacts(user_id, updated_at DESC, created_at DESC);
