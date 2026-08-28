-- Durable chat turns keep the expensive LLM operation separate from its SSE
-- connection. The partial unique index is the database-level single-flight
-- guard for one conversation.
CREATE TABLE chat_turns (
  id UUID PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  request_id VARCHAR(80) NOT NULL,
  question TEXT NOT NULL,
  trace_id VARCHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACCEPTED',
  attempts INT NOT NULL DEFAULT 0,
  lease_token UUID,
  lease_until TIMESTAMPTZ,
  answer_message_id BIGINT REFERENCES messages(id),
  last_error TEXT,
  cancel_requested_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, request_id),
  CHECK (status IN ('ACCEPTED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'))
);

CREATE UNIQUE INDEX uq_chat_turns_one_active_per_conversation
  ON chat_turns (conversation_id)
  WHERE status IN ('ACCEPTED', 'RUNNING');

CREATE INDEX idx_chat_turns_claim
  ON chat_turns (status, lease_until, created_at);

ALTER TABLE messages ADD COLUMN chat_turn_id UUID REFERENCES chat_turns(id);
CREATE UNIQUE INDEX uq_messages_chat_turn_id
  ON messages (chat_turn_id) WHERE chat_turn_id IS NOT NULL;
