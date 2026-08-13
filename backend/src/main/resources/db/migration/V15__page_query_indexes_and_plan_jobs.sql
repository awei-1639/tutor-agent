-- 页面读链路索引与计划异步任务表。
CREATE INDEX IF NOT EXISTS idx_notifications_user_id_desc
    ON notifications (user_id, id DESC);

CREATE INDEX IF NOT EXISTS idx_plan_tasks_user_day
    ON plan_tasks (user_id, day, id);

CREATE INDEX IF NOT EXISTS idx_checkins_user_status_task
    ON checkins (user_id, status, task_id);

CREATE TABLE IF NOT EXISTS plan_generation_jobs (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  goal TEXT NOT NULL,
  current_skills TEXT NOT NULL DEFAULT '',
  checkin_history TEXT NOT NULL DEFAULT '',
  trace_id VARCHAR(64),
  status VARCHAR(16) NOT NULL DEFAULT 'queued', -- queued / running / completed / failed
  plan_id BIGINT REFERENCES plans(id),
  error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_plan_generation_jobs_queue
    ON plan_generation_jobs (status, id);
CREATE INDEX IF NOT EXISTS idx_plan_generation_jobs_user
    ON plan_generation_jobs (user_id, id DESC);
