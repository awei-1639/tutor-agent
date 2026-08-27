-- 定时任务分布式互斥锁：多实例部署时，同一 cron 任务同一触发窗口只应由一个实例执行。
-- 通过 token 记账窗口/清理型任务天然幂等，但全量推送等副作用型任务重复执行会造成重复通知。
CREATE TABLE IF NOT EXISTS scheduled_task_locks (
    task_name TEXT PRIMARY KEY,
    locked_until TIMESTAMPTZ NOT NULL,
    owner TEXT NOT NULL
);
