-- 引用溯源：来源字段和消息级质量反馈。
ALTER TABLE kg_chunks ADD COLUMN IF NOT EXISTS source_url TEXT;
ALTER TABLE kg_chunks ADD COLUMN IF NOT EXISTS source_title TEXT;
ALTER TABLE kg_chunks ADD COLUMN IF NOT EXISTS source_author TEXT;
ALTER TABLE kg_chunks ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ;
ALTER TABLE kg_chunks ADD COLUMN IF NOT EXISTS retrieved_at TIMESTAMPTZ;

-- 一次回答的 trace 与消息绑定，便于把反馈关联到检索/模型链路。
ALTER TABLE messages ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_messages_trace ON messages(trace_id) WHERE trace_id IS NOT NULL;

CREATE TABLE message_feedback (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  conversation_id BIGINT NOT NULL REFERENCES conversations(id),
  message_id BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  trace_id VARCHAR(64),
  rating VARCHAR(16) NOT NULL CHECK (rating IN ('helpful', 'not_helpful')),
  reason VARCHAR(64),
  comment TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, message_id)
);
CREATE INDEX idx_message_feedback_trace ON message_feedback(trace_id) WHERE trace_id IS NOT NULL;
