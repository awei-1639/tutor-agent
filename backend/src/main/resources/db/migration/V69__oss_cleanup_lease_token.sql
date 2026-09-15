-- 为 OSS 清理补偿队列补上租约围栏 token：此前完成/失败只按 status='processing' 过滤，
-- 崩溃 worker 的迟到回调可能覆盖新 worker 的结果，且过期的 processing 行永远无人接管。
ALTER TABLE knowledge_oss_cleanup_jobs ADD COLUMN IF NOT EXISTS lease_token UUID;
