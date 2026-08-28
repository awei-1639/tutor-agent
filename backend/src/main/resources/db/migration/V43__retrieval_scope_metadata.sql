-- 检索范围元数据：既有 kg_chunks 属于全局种子数据，继续保持公开可读。
ALTER TABLE kg_chunks ADD COLUMN IF NOT EXISTS visibility VARCHAR(16) NOT NULL DEFAULT 'public';
ALTER TABLE kg_chunks ADD COLUMN IF NOT EXISTS owner_user_id BIGINT REFERENCES users(id);
ALTER TABLE kg_chunks ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(128);
ALTER TABLE kg_chunks ADD COLUMN IF NOT EXISTS source_document_id UUID REFERENCES knowledge_documents(id);

UPDATE kg_chunks SET visibility = 'public' WHERE visibility IS NULL OR visibility = '';

CREATE INDEX IF NOT EXISTS idx_kg_chunks_scope
    ON kg_chunks (visibility, owner_user_id, tenant_id, node_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_documents_owner_status
    ON knowledge_documents (created_by, status, deleted_at);
