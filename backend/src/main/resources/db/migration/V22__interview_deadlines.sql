ALTER TABLE interview_sessions
  ADD COLUMN deadline_at TIMESTAMPTZ;

UPDATE interview_sessions
SET deadline_at = COALESCE(completed_at, created_at + INTERVAL '45 minutes')
WHERE deadline_at IS NULL;

ALTER TABLE interview_sessions
  ALTER COLUMN deadline_at SET NOT NULL;

CREATE INDEX idx_interview_sessions_deadline
  ON interview_sessions(status, deadline_at)
  WHERE status = 'IN_PROGRESS';
