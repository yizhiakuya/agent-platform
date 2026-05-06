-- Server-side semantic photo index.
--
-- Original images stay on the Android device. The server stores metadata plus
-- a bounded thumbnail, then agent-service computes multimodal embeddings for
-- those thumbnails and writes them into photo_asset_embeddings.

CREATE TABLE photo_assets (
    id                 UUID PRIMARY KEY,
    user_id            UUID         NOT NULL,
    device_id          UUID         NOT NULL,
    media_store_id     TEXT         NOT NULL,
    name               TEXT         NOT NULL,
    bucket_id          TEXT         NULL,
    bucket_name        TEXT         NULL,
    date_taken_ms      BIGINT       NULL,
    date_modified_sec  BIGINT       NULL,
    size_bytes         BIGINT       NULL,
    width              INTEGER      NULL,
    height             INTEGER      NULL,
    mime_type          TEXT         NULL,
    content_hash       TEXT         NULL,
    thumb_b64          TEXT         NULL,
    indexed_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at         TIMESTAMPTZ  NULL,
    UNIQUE (device_id, media_store_id)
);

CREATE INDEX idx_photo_assets_user_updated
    ON photo_assets(user_id, updated_at DESC);
CREATE INDEX idx_photo_assets_user_date
    ON photo_assets(user_id, date_taken_ms DESC NULLS LAST);
CREATE INDEX idx_photo_assets_user_bucket
    ON photo_assets(user_id, bucket_id);

-- 1024 dims matches the current jina-embeddings-v3 memory migration and keeps
-- one pgvector width for v0. If a CLIP sidecar uses a different width later,
-- add a forward migration and update PHOTO_EMBEDDING_DIM.
CREATE TABLE photo_asset_embeddings (
    asset_id         UUID PRIMARY KEY REFERENCES photo_assets(id) ON DELETE CASCADE,
    embedding        public.vector(1024) NOT NULL,
    embedding_model  TEXT         NOT NULL,
    embedding_dim    INTEGER      NOT NULL,
    embedded_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_photo_asset_embeddings_hnsw
    ON photo_asset_embeddings USING hnsw (embedding public.vector_cosine_ops);
