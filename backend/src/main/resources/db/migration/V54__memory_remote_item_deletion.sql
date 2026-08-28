-- 保存 Mem0 远端 UUID，使单条本地记忆删除可以产生精确的远端删除事件。
ALTER TABLE episodes
    ADD COLUMN IF NOT EXISTS remote_memory_id VARCHAR(128);

ALTER TABLE memory_sync_outbox
    ADD COLUMN IF NOT EXISTS remote_memory_id VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_episodes_remote_memory
    ON episodes (user_id, remote_memory_id)
    WHERE remote_memory_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_memory_sync_delete_item
    ON memory_sync_outbox (user_id, operation, memory_id, memory_generation)
    WHERE operation='delete_memory' AND memory_id IS NOT NULL;
