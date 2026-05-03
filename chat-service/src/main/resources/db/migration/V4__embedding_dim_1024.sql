-- Switch embedding model from text-embedding-3-small (1536-dim, OpenAI) to
-- jina-embeddings-v3 (1024-dim). pgvector's vector(N) type is fixed-width,
-- so the column has to be dropped and recreated.
--
-- Safe to do unconditionally: sub2api never exposed /v1/embeddings, so every
-- recall/store call 404'd and both memory_facts and memory_embeddings are
-- empty in production. Verified before applying:
--   select count(*) from chat.memory_embeddings;  -- 0
--   select count(*) from chat.memory_facts;       -- 0

DROP INDEX IF EXISTS idx_memory_embeddings_hnsw;

ALTER TABLE memory_embeddings DROP COLUMN embedding;
ALTER TABLE memory_embeddings ADD COLUMN embedding public.vector(1024) NOT NULL;

CREATE INDEX idx_memory_embeddings_hnsw
    ON memory_embeddings USING hnsw (embedding public.vector_cosine_ops);
