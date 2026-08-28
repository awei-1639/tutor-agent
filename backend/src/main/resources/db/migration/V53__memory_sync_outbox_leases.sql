-- 为外部记忆同步任务增加租约和 fencing token，避免 worker 崩溃后任务永久卡住。
ALTER TABLE memory_sync_outbox
    ADD COLUMN IF NOT EXISTS lease_token UUID,
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ;

UPDATE memory_sync_outbox
SET lease_until=now()
WHERE status='processing' AND lease_until IS NULL;

CREATE INDEX IF NOT EXISTS idx_memory_sync_outbox_lease
    ON memory_sync_outbox (status, lease_until, id);
