CREATE TABLE interview_feedback (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  session_id VARCHAR(36) NOT NULL REFERENCES interview_sessions(id) ON DELETE CASCADE,
  rating VARCHAR(16) NOT NULL CHECK (rating IN ('accurate', 'inaccurate')),
  reason TEXT NOT NULL DEFAULT '',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(user_id, session_id)
);

CREATE INDEX idx_interview_feedback_rating_created
  ON interview_feedback(rating, created_at DESC);
