-- 同一用户对同一岗位只保留一条推送任务；NULL job_id 的系统任务仍可重复创建。
CREATE UNIQUE INDEX IF NOT EXISTS uq_push_tasks_user_job
    ON push_tasks (user_id, job_id)
    WHERE job_id IS NOT NULL;
