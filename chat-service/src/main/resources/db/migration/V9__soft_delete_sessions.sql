-- Preserve chat trajectories when the user removes a session from the UI.
-- Normal APIs filter deleted rows; retained rows keep messages/artifacts for
-- future audit/export/reset tooling.

ALTER TABLE sessions
    ADD COLUMN deleted_at TIMESTAMPTZ;

CREATE INDEX idx_sessions_user_deleted_created
    ON sessions(user_id, deleted_at, created_at DESC);
