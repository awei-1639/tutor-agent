ALTER TABLE interview_completion_jobs
  ADD COLUMN IF NOT EXISTS evidence_status VARCHAR(16) NOT NULL DEFAULT 'queued',
  ADD COLUMN IF NOT EXISTS learning_plan_status VARCHAR(16) NOT NULL DEFAULT 'queued';
