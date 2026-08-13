-- A retest is a new session, never a rewrite of the original assessment.
ALTER TABLE interview_sessions
  ADD COLUMN retest_of VARCHAR(36) REFERENCES interview_sessions(id);
CREATE INDEX idx_interview_sessions_user_retest
  ON interview_sessions(user_id, retest_of, created_at DESC);
