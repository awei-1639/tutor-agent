-- Memory is derived data.  A generation invalidates work queued before a user
-- clears their cross-session memory, preventing delayed jobs from reviving it.
ALTER TABLE users ADD COLUMN IF NOT EXISTS memory_generation BIGINT NOT NULL DEFAULT 0;

ALTER TABLE episodes ADD COLUMN IF NOT EXISTS source_from_msg_id BIGINT;
ALTER TABLE episodes ADD COLUMN IF NOT EXISTS source_to_msg_id BIGINT;
ALTER TABLE episodes ADD COLUMN IF NOT EXISTS memory_generation BIGINT NOT NULL DEFAULT 0;
ALTER TABLE episodes ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;
ALTER TABLE episodes ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'active';

CREATE UNIQUE INDEX IF NOT EXISTS uq_episodes_source_window
    ON episodes (user_id, conversation_id, source_from_msg_id, source_to_msg_id)
    WHERE source_from_msg_id IS NOT NULL AND source_to_msg_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_episodes_active_expiry
    ON episodes (user_id, expires_at, created_at DESC)
    WHERE status = 'active';

-- The outbox is deliberately local and transactional.  A worker can be added
-- without returning to an unsafe request-thread double write to Mem0.
CREATE TABLE IF NOT EXISTS memory_sync_outbox (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    memory_generation BIGINT NOT NULL,
    operation VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_memory_sync_outbox_pending
    ON memory_sync_outbox (status, next_attempt_at, id);
