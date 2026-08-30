-- 用户级日配额与后台任务子预算 (P1)。
-- trace 归属：回合编排方先把 trace 写入 llm_turn_budget.user_id，
-- 之后该 trace 内所有网关调用在预留时按归属用户扣 llm_user_budget。
ALTER TABLE llm_turn_budget ADD COLUMN IF NOT EXISTS user_id BIGINT;

CREATE TABLE IF NOT EXISTS llm_user_budget (
  user_id BIGINT NOT NULL,
  budget_day DATE NOT NULL,
  reserved_tokens BIGINT NOT NULL DEFAULT 0,
  actual_tokens BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, budget_day)
);

-- 后台任务 (摘要/抽取/批量嵌入) 在全局日限额内可占用的子预算，防后台挤占前台。
ALTER TABLE llm_daily_budget
  ADD COLUMN IF NOT EXISTS background_reserved_tokens BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS background_actual_tokens BIGINT NOT NULL DEFAULT 0;
