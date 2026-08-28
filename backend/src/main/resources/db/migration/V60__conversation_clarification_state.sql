-- Persist one-turn clarification state so a resumed request can be interpreted
-- as an answer to the pending choice after a restart or across instances.
ALTER TABLE conversations
    ADD COLUMN IF NOT EXISTS clarification_pending BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS clarification_intent VARCHAR(16),
    ADD COLUMN IF NOT EXISTS clarification_expires_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_conversations_clarification_pending
    ON conversations (id) WHERE clarification_pending = TRUE;
