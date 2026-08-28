-- 直传 OSS 使大体积请求体不进入 API 进程。
CREATE TABLE IF NOT EXISTS knowledge_upload_sessions (
  document_id UUID PRIMARY KEY REFERENCES knowledge_documents(id) ON DELETE CASCADE,
  admin_user_id BIGINT NOT NULL REFERENCES users(id),
  object_key TEXT NOT NULL UNIQUE,
  expected_size BIGINT NOT NULL CHECK (expected_size > 0),
  expires_at TIMESTAMPTZ NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'pending',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ,
  CONSTRAINT knowledge_upload_sessions_status_check CHECK (status IN ('pending', 'completed', 'expired'))
);

CREATE INDEX IF NOT EXISTS idx_knowledge_upload_sessions_expiry
  ON knowledge_upload_sessions(status, expires_at);
