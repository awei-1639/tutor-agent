ALTER TABLE knowledge_document_chunk_staging ADD COLUMN IF NOT EXISTS lease_token UUID;
UPDATE knowledge_document_chunk_staging s
SET lease_token = j.lease_token
FROM knowledge_ingestion_jobs j
WHERE s.job_id=j.id AND s.lease_token IS NULL;
