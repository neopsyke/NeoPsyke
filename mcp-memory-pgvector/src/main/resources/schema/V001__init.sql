CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS memories (
    id              BIGSERIAL PRIMARY KEY,
    namespace       TEXT NOT NULL DEFAULT 'default',
    content         TEXT NOT NULL,
    embedding       vector({{EMBEDDING_DIMENSIONS}}),
    source          TEXT NOT NULL DEFAULT 'unknown',
    confidence      DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    tags            TEXT[] NOT NULL DEFAULT '{}',
    fingerprint     TEXT NOT NULL,
    metadata        JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE memories ADD COLUMN IF NOT EXISTS namespace TEXT NOT NULL DEFAULT 'default';
ALTER TABLE memories ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE memories ADD COLUMN IF NOT EXISTS supersedes_memory_id BIGINT NULL REFERENCES memories(id);
ALTER TABLE memories ADD COLUMN IF NOT EXISTS fact_subject TEXT NULL;
ALTER TABLE memories ADD COLUMN IF NOT EXISTS fact_key TEXT NULL;
ALTER TABLE memories ADD COLUMN IF NOT EXISTS fact_value TEXT NULL;
ALTER TABLE memories ADD COLUMN IF NOT EXISTS versioned_at TIMESTAMPTZ NULL;

-- Best-effort metadata backfill for future fact-aware writes.
UPDATE memories
SET fact_subject = NULLIF(metadata->>'fact_subject', '')
WHERE fact_subject IS NULL
  AND metadata ? 'fact_subject';

UPDATE memories
SET fact_key = NULLIF(metadata->>'fact_key', '')
WHERE fact_key IS NULL
  AND metadata ? 'fact_key';

UPDATE memories
SET fact_value = NULLIF(metadata->>'fact_value', '')
WHERE fact_value IS NULL
  AND metadata ? 'fact_value';

DROP INDEX IF EXISTS idx_memories_fingerprint;
CREATE UNIQUE INDEX IF NOT EXISTS idx_memories_namespace_fingerprint ON memories(namespace, fingerprint);
CREATE INDEX IF NOT EXISTS idx_memories_embedding_hnsw ON memories
    USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);

CREATE INDEX IF NOT EXISTS idx_memories_namespace ON memories (namespace);
CREATE INDEX IF NOT EXISTS idx_memories_tags_gin ON memories USING gin (tags);

CREATE INDEX IF NOT EXISTS idx_memories_created_at ON memories (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_memories_source ON memories (source);

CREATE INDEX IF NOT EXISTS idx_memories_confidence ON memories (confidence) WHERE confidence >= 0.5;

CREATE INDEX IF NOT EXISTS idx_memories_active ON memories (namespace, is_active);
CREATE INDEX IF NOT EXISTS idx_memories_supersedes_memory_id ON memories (supersedes_memory_id);
CREATE INDEX IF NOT EXISTS idx_memories_fact_lookup ON memories (namespace, fact_subject, fact_key, is_active);
CREATE UNIQUE INDEX IF NOT EXISTS idx_memories_active_fact_unique
    ON memories(namespace, fact_subject, fact_key)
    WHERE is_active = TRUE
      AND fact_subject IS NOT NULL
      AND fact_key IS NOT NULL;
