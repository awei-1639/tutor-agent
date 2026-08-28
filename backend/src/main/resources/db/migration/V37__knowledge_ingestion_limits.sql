ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS partial_indexed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS truncation_reason TEXT;
