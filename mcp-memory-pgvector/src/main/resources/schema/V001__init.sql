CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS memories (
    id              BIGSERIAL PRIMARY KEY,
    content         TEXT NOT NULL,
    embedding       vector(1024),
    source          TEXT NOT NULL DEFAULT 'unknown',
    confidence      DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    tags            TEXT[] NOT NULL DEFAULT '{}',
    fingerprint     TEXT NOT NULL,
    metadata        JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_memories_fingerprint ON memories(fingerprint);

CREATE INDEX IF NOT EXISTS idx_memories_embedding_hnsw ON memories
    USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);

CREATE INDEX IF NOT EXISTS idx_memories_tags_gin ON memories USING gin (tags);

CREATE INDEX IF NOT EXISTS idx_memories_created_at ON memories (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_memories_source ON memories (source);

CREATE INDEX IF NOT EXISTS idx_memories_confidence ON memories (confidence) WHERE confidence >= 0.5;
