-- 计划生成队列补上重试计数：此前 running 行租约过期后可被无限接管（无上限、无死信），
-- worker 反复崩溃时会无限重跑 LLM 生成。对齐其他队列的 3 次上限 + 死信回收。
ALTER TABLE plan_generation_jobs ADD COLUMN IF NOT EXISTS attempts INT NOT NULL DEFAULT 0;
