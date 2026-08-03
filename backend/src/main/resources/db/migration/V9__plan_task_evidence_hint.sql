-- 任务的完成标准可见化：不再只记录“做什么”，同时记录“如何证明完成”。
ALTER TABLE plan_tasks ADD COLUMN IF NOT EXISTS evidence_hint TEXT;
