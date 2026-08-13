-- Persist provenance at ingestion so the chat request path never has to fetch arbitrary URLs.
ALTER TABLE kg_chunks ADD COLUMN IF NOT EXISTS source_status VARCHAR(24);
ALTER TABLE kg_chunks ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);

UPDATE kg_chunks
SET content_hash = encode(digest(convert_to(chunk_text, 'UTF8'), 'sha256'), 'hex')
WHERE content_hash IS NULL;

UPDATE kg_chunks
SET source_status = CASE WHEN source_url IS NULL OR btrim(source_url) = '' THEN 'missing' ELSE 'unverified' END
WHERE source_status IS NULL;

ALTER TABLE kg_chunks ALTER COLUMN source_status SET DEFAULT 'missing';
ALTER TABLE kg_chunks ALTER COLUMN source_status SET NOT NULL;
ALTER TABLE kg_chunks ADD CONSTRAINT ck_kg_chunks_source_status
  CHECK (source_status IN ('missing', 'unverified', 'verified', 'managed'));
ALTER TABLE kg_chunks ADD CONSTRAINT ck_kg_chunks_content_hash
  CHECK (content_hash IS NULL OR content_hash ~ '^[0-9a-f]{64}$');

CREATE INDEX IF NOT EXISTS idx_kg_chunks_source_status ON kg_chunks(source_status);
