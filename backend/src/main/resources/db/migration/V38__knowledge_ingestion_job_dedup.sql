-- Only one live generation job may be claimable at a time. Historical completed
-- and terminal failed jobs remain available for audit.
CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_ingestion_active_job
    ON knowledge_ingestion_jobs (document_id, document_generation)
    WHERE status IN ('pending', 'processing', 'retryable_failed');
