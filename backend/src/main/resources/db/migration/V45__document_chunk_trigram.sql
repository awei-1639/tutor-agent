-- 稀疏检索也面向受管文档分块；保持与 kg_chunks 一致的 pg_trgm 访问路径，
-- 以免 % 查询退化为全表扫描。
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_knowledge_document_chunks_text_trgm
    ON knowledge_document_chunks USING gin (chunk_text gin_trgm_ops);
