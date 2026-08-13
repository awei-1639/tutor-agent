-- Stores aggregate metrics only; raw answers and personally identifying text are never persisted here.
CREATE TABLE IF NOT EXISTS interview_score_eval_runs (
  id BIGSERIAL PRIMARY KEY,
  dataset_version VARCHAR(128) NOT NULL,
  case_count INTEGER NOT NULL,
  metrics JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_interview_score_eval_runs_created_at
    ON interview_score_eval_runs(created_at DESC);
