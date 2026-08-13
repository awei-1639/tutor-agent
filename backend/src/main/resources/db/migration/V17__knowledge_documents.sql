-- 管理端知识库：原文件在 OSS，数据库只保存元数据、解析状态、分块与向量。
CREATE TABLE knowledge_documents (
  id UUID PRIMARY KEY,
  title TEXT NOT NULL,
  original_filename TEXT NOT NULL,
  content_type VARCHAR(128),
  size_bytes BIGINT NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  oss_object_key TEXT NOT NULL UNIQUE,
  status VARCHAR(16) NOT NULL DEFAULT 'uploaded', -- uploaded / processing / indexed / failed / deleted
  error_message TEXT,
  chunk_count INT NOT NULL DEFAULT 0,
  created_by BIGINT NOT NULL REFERENCES users(id),
  deleted_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_knowledge_documents_status_created ON knowledge_documents(status, created_at DESC);
CREATE INDEX idx_knowledge_documents_hash ON knowledge_documents(content_hash) WHERE deleted_at IS NULL;

CREATE TABLE knowledge_document_chunks (
  id BIGSERIAL PRIMARY KEY,
  document_id UUID NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
  chunk_index INT NOT NULL,
  chunk_text TEXT NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  embedding vector(1024) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(document_id, chunk_index)
);
CREATE INDEX idx_knowledge_document_chunks_embedding
  ON knowledge_document_chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_knowledge_document_chunks_document ON knowledge_document_chunks(document_id, chunk_index);
