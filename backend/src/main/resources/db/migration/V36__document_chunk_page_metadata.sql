ALTER TABLE knowledge_document_chunks ADD COLUMN IF NOT EXISTS page_from INT;
ALTER TABLE knowledge_document_chunks ADD COLUMN IF NOT EXISTS page_to INT;
ALTER TABLE knowledge_document_chunk_staging ADD COLUMN IF NOT EXISTS page_from INT;
ALTER TABLE knowledge_document_chunk_staging ADD COLUMN IF NOT EXISTS page_to INT;
