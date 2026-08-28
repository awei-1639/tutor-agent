-- 为已准入的 Episode 增加可重试的 Mem0 副本同步事件。
ALTER TABLE memory_sync_outbox
    ADD COLUMN IF NOT EXISTS memory_id BIGINT,
    ADD COLUMN IF NOT EXISTS summary TEXT,
    ADD COLUMN IF NOT EXISTS topics TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS open_items TEXT[] NOT NULL DEFAULT '{}';

CREATE UNIQUE INDEX IF NOT EXISTS uq_memory_sync_upsert
    ON memory_sync_outbox (operation, memory_id, memory_generation)
    WHERE operation = 'upsert_memory' AND memory_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_memory_sync_outbox_user_memory
    ON memory_sync_outbox (user_id, memory_id, operation);
