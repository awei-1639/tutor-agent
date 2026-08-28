CREATE TABLE tool_idempotency (
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  tool VARCHAR(32) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL,
  result JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, tool, idempotency_key),
  CONSTRAINT chk_tool_idempotency_status CHECK (status IN ('RUNNING', 'COMPLETED'))
);
CREATE INDEX idx_tool_idempotency_running_updated ON tool_idempotency(updated_at)
    WHERE status = 'RUNNING';
