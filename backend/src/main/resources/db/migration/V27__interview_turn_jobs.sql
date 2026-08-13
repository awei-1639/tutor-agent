-- Each candidate answer is a durable unit of LLM work. It survives restarts and
-- prevents provider latency from extending a database transaction.
CREATE TABLE interview_turn_jobs (
  id VARCHAR(36) PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  session_id VARCHAR(36) NOT NULL REFERENCES interview_sessions(id) ON DELETE CASCADE,
  question_sequence INT NOT NULL,
  request_id VARCHAR(80) NOT NULL,
  answer TEXT NOT NULL,
  trace_id VARCHAR(128) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  lease_until TIMESTAMPTZ,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  response_status VARCHAR(24),
  response_message TEXT,
  last_error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (session_id, request_id),
  CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'RETRYABLE_FAILED', 'FAILED'))
);
CREATE INDEX idx_interview_turn_jobs_claim
  ON interview_turn_jobs (status, next_attempt_at, created_at);
