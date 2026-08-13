-- Durable post-interview work queue. Completion is committed independently from downstream learning tasks.
CREATE TABLE IF NOT EXISTS interview_completion_jobs (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  session_id VARCHAR(64) NOT NULL REFERENCES interview_sessions(id),
  status VARCHAR(16) NOT NULL DEFAULT 'queued', -- queued / running / completed / failed
  attempts INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ,
  UNIQUE (session_id)
);
CREATE INDEX IF NOT EXISTS idx_interview_completion_jobs_queue
    ON interview_completion_jobs (status, id);
