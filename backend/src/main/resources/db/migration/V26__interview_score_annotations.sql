-- Human calibration labels are separated from candidate answers and are visible only to administrators.
CREATE TABLE IF NOT EXISTS interview_score_annotations (
  id BIGSERIAL PRIMARY KEY,
  question_id BIGINT NOT NULL REFERENCES interview_questions(id) ON DELETE CASCADE,
  reviewer_id BIGINT NOT NULL REFERENCES users(id),
  human_score INTEGER NOT NULL CHECK (human_score BETWEEN 0 AND 10),
  rationale VARCHAR(2000) NOT NULL DEFAULT '',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (question_id, reviewer_id)
);
CREATE INDEX IF NOT EXISTS idx_interview_score_annotations_question
    ON interview_score_annotations(question_id, reviewer_id);
