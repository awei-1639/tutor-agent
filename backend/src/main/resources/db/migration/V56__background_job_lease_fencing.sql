-- 统一异步任务的领取令牌。租约过期后被新 worker 接管时，旧 worker 不得再覆盖新状态。
ALTER TABLE plan_generation_jobs
    ADD COLUMN IF NOT EXISTS lease_token UUID,
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ;

ALTER TABLE interview_completion_jobs
    ADD COLUMN IF NOT EXISTS lease_token UUID,
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ;

ALTER TABLE interview_turn_jobs
    ADD COLUMN IF NOT EXISTS lease_token UUID;

CREATE INDEX IF NOT EXISTS idx_plan_generation_jobs_lease
    ON plan_generation_jobs (status, lease_until, id);

CREATE INDEX IF NOT EXISTS idx_interview_completion_jobs_lease
    ON interview_completion_jobs (status, lease_until, id);
