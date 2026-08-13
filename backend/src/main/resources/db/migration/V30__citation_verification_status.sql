-- Citation verification is asynchronous, but its state must remain auditable.
ALTER TABLE messages
  ADD COLUMN IF NOT EXISTS citation_status VARCHAR(24) NOT NULL DEFAULT 'not_applicable',
  ADD COLUMN IF NOT EXISTS citation_issues JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS citation_checked_at TIMESTAMPTZ;

ALTER TABLE messages
  DROP CONSTRAINT IF EXISTS messages_citation_status_check;
ALTER TABLE messages
  ADD CONSTRAINT messages_citation_status_check
  CHECK (citation_status IN ('not_applicable', 'pending', 'verified', 'unsupported', 'invalid_reference', 'unavailable'));

CREATE INDEX IF NOT EXISTS idx_messages_citation_status
  ON messages (citation_status) WHERE citation_status <> 'not_applicable';
