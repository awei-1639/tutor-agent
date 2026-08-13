-- P1 interview assessment metadata. The blueprint is immutable for a session,
-- while scorecards and evidence retain why an assessment was made.
CREATE TABLE interview_blueprints (
  id VARCHAR(36) PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  target_role TEXT NOT NULL DEFAULT '',
  topic TEXT NOT NULL,
  skill_ids TEXT[] NOT NULL DEFAULT '{}',
  round_plan JSONB NOT NULL DEFAULT '{}'::jsonb,
  version VARCHAR(32) NOT NULL DEFAULT 'v1',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_interview_blueprints_user_created
  ON interview_blueprints(user_id, created_at DESC);

ALTER TABLE interview_sessions
  ADD COLUMN blueprint_id VARCHAR(36) REFERENCES interview_blueprints(id),
  ADD COLUMN skill_ids TEXT[] NOT NULL DEFAULT '{}';

ALTER TABLE interview_questions
  ADD COLUMN skill_id TEXT,
  ADD COLUMN scorecard JSONB;

CREATE TABLE interview_skill_evidence (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  session_id VARCHAR(36) NOT NULL REFERENCES interview_sessions(id) ON DELETE CASCADE,
  skill_id TEXT NOT NULL,
  average_score NUMERIC(4,2) NOT NULL,
  confidence NUMERIC(3,2) NOT NULL,
  evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(session_id, skill_id)
);
CREATE INDEX idx_interview_skill_evidence_user_skill
  ON interview_skill_evidence(user_id, skill_id, created_at DESC);
