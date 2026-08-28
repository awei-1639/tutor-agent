ALTER TABLE knowledge_ingestion_jobs ADD COLUMN IF NOT EXISTS lease_token UUID;

CREATE TABLE IF NOT EXISTS knowledge_oss_cleanup_jobs (
  id UUID PRIMARY KEY,
  object_key TEXT NOT NULL UNIQUE,
  reason VARCHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'pending',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  lease_until TIMESTAMPTZ,
  last_error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_knowledge_oss_cleanup_claim
  ON knowledge_oss_cleanup_jobs(status, next_attempt_at, created_at);

CREATE TABLE IF NOT EXISTS knowledge_embedding_cache (
  content_hash VARCHAR(64) NOT NULL,
  model_name TEXT NOT NULL,
  embedding vector(1024) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (content_hash, model_name)
);
