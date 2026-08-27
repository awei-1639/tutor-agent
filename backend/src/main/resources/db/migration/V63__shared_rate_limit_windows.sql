-- 通用固定窗口限流表：多实例共享同一窗口，替代各处进程内 ConcurrentHashMap。
-- scope 区分不同限流用途 (chat / interview_open / interview_answer / knowledge_upload)。
CREATE TABLE IF NOT EXISTS rate_limit_windows (
    scope TEXT NOT NULL,
    subject_id BIGINT NOT NULL,
    window_started_at TIMESTAMPTZ NOT NULL,
    request_count INT NOT NULL,
    PRIMARY KEY (scope, subject_id)
);
