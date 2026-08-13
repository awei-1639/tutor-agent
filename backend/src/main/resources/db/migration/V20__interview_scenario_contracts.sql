-- Scenario inputs and per-question assessment contracts make an interview
-- reproducible instead of relying on an opaque, free-form model prompt.
ALTER TABLE interview_blueprints
  ADD COLUMN job_description TEXT NOT NULL DEFAULT '',
  ADD COLUMN interview_type VARCHAR(32) NOT NULL DEFAULT 'technical',
  ADD COLUMN difficulty VARCHAR(16) NOT NULL DEFAULT 'MID',
  ADD COLUMN duration_minutes INT NOT NULL DEFAULT 45;

ALTER TABLE interview_questions
  ADD COLUMN assessment_contract JSONB NOT NULL DEFAULT '{}'::jsonb;
