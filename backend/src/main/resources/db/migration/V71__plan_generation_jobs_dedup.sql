-- 同一用户同时至多一个可领取的计划生成任务, 杜绝重复计划写入 (对照 V38 的 knowledge 模式)。
-- 完成与终态 failed 的历史任务不受影响, 保留供审计。
CREATE UNIQUE INDEX IF NOT EXISTS uq_plan_generation_active_job
    ON plan_generation_jobs (user_id)
    WHERE status IN ('queued', 'running');
