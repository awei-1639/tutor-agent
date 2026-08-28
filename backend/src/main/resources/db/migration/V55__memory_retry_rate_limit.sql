-- 将手动远程删除重试限流放入 PostgreSQL，保证多实例共享同一窗口。
CREATE TABLE IF NOT EXISTS memory_retry_rate_limits (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    window_started_at TIMESTAMPTZ NOT NULL,
    request_count INT NOT NULL
);
