-- Phase 2 混合检索 (V4 2.1): 稀疏通道用 pg_trgm 模糊匹配, 解决 bge-m3 稠密检索对
-- 专有名词(LoRA/vLLM/RAG)召回不稳的问题。GIN 索引保证毫秒级 trigram 匹配。
-- pg_trgm 优势: 无中文分词依赖, 三字符 gram 模糊命中专有名词片段;
-- 不引入 zhparser/jieba 等外部依赖, 实施快且 Phase 4 真实岗位源接入时可继续沿用。

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- GIN trigram 索引 (gin_trgm_ops): 支持 ILIKE / ~ / ~~* 模糊查询与 %s% 包含
CREATE INDEX idx_kg_chunks_chunktext_trgm
  ON kg_chunks USING gin (chunk_text gin_trgm_ops);