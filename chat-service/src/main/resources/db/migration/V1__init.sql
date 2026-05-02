-- chat-service initial schema (Flyway V1)
-- The 'chat' schema is owned by the agent_chat role and pre-created by
-- infra/postgres/init/01-init.sh.

CREATE TABLE sessions (
    id              UUID PRIMARY KEY,
    user_id         UUID         NOT NULL,
    title           VARCHAR(256),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_message_at TIMESTAMPTZ
);
CREATE INDEX idx_sessions_user_id ON sessions(user_id);
CREATE INDEX idx_sessions_user_last_msg ON sessions(user_id, last_message_at DESC NULLS LAST);

CREATE TABLE messages (
    id         UUID PRIMARY KEY,
    session_id UUID         NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    role       VARCHAR(32)  NOT NULL,
    content    TEXT         NOT NULL,
    metadata   TEXT,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_messages_session_id      ON messages(session_id);
CREATE INDEX idx_messages_session_created ON messages(session_id, created_at);
