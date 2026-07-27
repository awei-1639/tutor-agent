-- Phase 3 V4 3.2: 学习计划闭环三表
-- plans: 周计划顶层 (goal + 时间窗 + 调整理由)
-- tasks: 每日任务卡片 (plan_id + 内容 + 类型 + 关联 evidence)
-- checkins: 打卡 (task_id + 状态 + 完成时间)
CREATE TABLE plans (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  goal TEXT NOT NULL,
  week_start DATE NOT NULL,
  week_end DATE NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'active',  -- active / completed / archived
  adjust_reason TEXT,                            -- 重规划触发时填充
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_plans_user_status ON plans(user_id, status, week_start);

CREATE TABLE plan_tasks (
  id BIGSERIAL PRIMARY KEY,
  plan_id BIGINT NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL REFERENCES users(id),
  day DATE NOT NULL,
  content TEXT NOT NULL,                          -- "复习 Python 基础 2h"
  kind VARCHAR(16) NOT NULL DEFAULT 'learn',     -- learn / practice / review
  related_node_ids TEXT[],                       -- 关联图谱节点 (技能/资源)
  estimated_minutes INT NOT NULL DEFAULT 60,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_plan_tasks_plan_day ON plan_tasks(plan_id, day);

CREATE TABLE checkins (
  id BIGSERIAL PRIMARY KEY,
  task_id BIGINT NOT NULL REFERENCES plan_tasks(id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL REFERENCES users(id),
  status VARCHAR(16) NOT NULL,                   -- done / skipped / partial
  feedback TEXT,                                  -- 用户反馈 "过难"/"过易"
  checked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_checkins_user_time ON checkins(user_id, checked_at);