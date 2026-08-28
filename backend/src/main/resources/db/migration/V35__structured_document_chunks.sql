ALTER TABLE knowledge_document_chunks ADD COLUMN IF NOT EXISTS section_path TEXT;
ALTER TABLE knowledge_document_chunks ADD COLUMN IF NOT EXISTS block_type VARCHAR(24) NOT NULL DEFAULT 'paragraph';
ALTER TABLE knowledge_document_chunks ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}';

ALTER TABLE knowledge_document_chunk_staging ADD COLUMN IF NOT EXISTS section_path TEXT;
ALTER TABLE knowledge_document_chunk_staging ADD COLUMN IF NOT EXISTS block_type VARCHAR(24) NOT NULL DEFAULT 'paragraph';
ALTER TABLE knowledge_document_chunk_staging ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}';

CREATE INDEX IF NOT EXISTS idx_knowledge_document_chunks_section
  ON knowledge_document_chunks (document_id, section_path);
