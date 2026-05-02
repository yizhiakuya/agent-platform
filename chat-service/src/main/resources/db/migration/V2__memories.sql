-- chat-service long-term memory schema (Flyway V2)
-- Pgvector extension is created by infra/postgres/init/02-pgvector.sh as superuser
-- (agent_chat lacks privilege to CREATE EXTENSION).

-- Long-term memory facts extracted from conversations.
-- kind: 'fact' (objective info), 'preference' (user pref), 'rule' (user-stated rule)
CREATE TABLE memory_facts (
    id                 UUID PRIMARY KEY,
    user_id            UUID         NOT NULL,
    kind               VARCHAR(32)  NOT NULL,
    content            TEXT         NOT NULL,
    source_message_id  UUID         NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_memory_facts_user ON memory_facts(user_id, created_at DESC);

-- Embeddings table (1:1 with memory_facts; split for hot/cold separation).
-- text-embedding-3-small returns 1536 dims. If we ever change the model,
-- this column type must change too — vector(N) is fixed-width.
-- vector type is fully-qualified because chat-service connects with
-- ?currentSchema=chat, which overrides agent_chat's default search_path
-- and would otherwise hide public.vector created by 02-pgvector.sh.
CREATE TABLE memory_embeddings (
    fact_id    UUID PRIMARY KEY REFERENCES memory_facts(id) ON DELETE CASCADE,
    embedding  public.vector(1536) NOT NULL
);
CREATE INDEX idx_memory_embeddings_hnsw
    ON memory_embeddings USING hnsw (embedding public.vector_cosine_ops);
