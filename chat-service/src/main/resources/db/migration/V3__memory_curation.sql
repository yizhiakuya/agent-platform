-- Promote a subset of memory_facts to "curated" — these are the high-confidence,
-- often-repeated facts that should be retrieved first during RAG. Raw facts stay
-- in the table as a backstop.
ALTER TABLE memory_facts ADD COLUMN is_curated BOOLEAN NOT NULL DEFAULT false;
-- access_count tracked so we can promote facts that keep showing up.
ALTER TABLE memory_facts ADD COLUMN access_count INT NOT NULL DEFAULT 0;
-- Set when a fact is promoted; useful for "demote stale curated" logic later.
ALTER TABLE memory_facts ADD COLUMN curated_at TIMESTAMPTZ NULL;

CREATE INDEX idx_memory_facts_curated_user
    ON memory_facts (user_id, is_curated DESC, created_at DESC);
