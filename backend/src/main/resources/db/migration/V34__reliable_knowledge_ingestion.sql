ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS generation BIGINT NOT NULL DEFAULT 0;
ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS indexed_generation BIGINT;

CREATE TABLE knowledge_ingestion_jobs (
  id UUID PRIMARY KEY,
  document_id UUID NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
  document_generation BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'pending',
  stage VARCHAR(24) NOT NULL DEFAULT 'queued',
  attempts INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 5,
  lease_until TIMESTAMPTZ,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  error_message TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ
);
CREATE INDEX idx_knowledge_ingestion_jobs_claim
  ON knowledge_ingestion_jobs (status, next_attempt_at, created_at);

CREATE TABLE knowledge_document_chunk_staging (
  job_id UUID NOT NULL REFERENCES knowledge_ingestion_jobs(id) ON DELETE CASCADE,
  chunk_index INT NOT NULL,
  chunk_text TEXT NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  embedding vector(1024) NOT NULL,
  PRIMARY KEY (job_id, chunk_index)
);
