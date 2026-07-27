-- Phase 1 core schema. 依据: 核心实现设计_Java版 第二章/8.2 + V3方案 5.2
-- episodes 表属 Phase 3, 建表随首批迁移(成本为零), 服务逻辑严格按阶段实施。

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============ 用户与画像 (L3) ============
CREATE TABLE users (
  id BIGSERIAL PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE profiles (
  user_id BIGINT PRIMARY KEY REFERENCES users(id),
  -- 画像字段带 confidence/source, 结构: {"skills":[{"name","confidence","source","last_seen"}], ...}
  data JSONB NOT NULL DEFAULT '{}',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE profile_events (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  delta JSONB NOT NULL,
  trigger VARCHAR(32) NOT NULL,          -- explicit / inferred / decay / confirm
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============ 会话与消息 (L1) ============
CREATE TABLE conversations (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  summary TEXT,                          -- 滚动摘要, 惰性更新
  summary_upto_msg_id BIGINT,
  last_active_at TIMESTAMPTZ,
  episodic_done BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE messages (
  id BIGSERIAL PRIMARY KEY,
  conversation_id BIGINT NOT NULL REFERENCES conversations(id),
  role VARCHAR(16) NOT NULL,             -- user / assistant
  content TEXT NOT NULL,
  intent VARCHAR(16),
  citations JSONB,                       -- ["skill:nlp", "res:cs224n"]
  expert_outputs JSONB,
  token_count INT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_messages_conv ON messages(conversation_id);

-- ============ 情景记忆 (L2, Phase 3 启用) ============
CREATE TABLE episodes (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  conversation_id BIGINT REFERENCES conversations(id),
  summary TEXT NOT NULL,
  topics TEXT[],
  open_items TEXT[],
  embedding vector(1024),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_episodes_embedding ON episodes USING hnsw (embedding vector_cosine_ops);

-- ============ 知识层 ============
CREATE TABLE kg_chunks (
  id BIGSERIAL PRIMARY KEY,
  node_id VARCHAR(128) NOT NULL UNIQUE,  -- 关联 Neo4j 节点
  node_type VARCHAR(16) NOT NULL,        -- skill / resource / job / company / community
  chunk_text TEXT NOT NULL,              -- 模板化序列化: 类型|名称|关键属性|一跳关系摘要
  embedding vector(1024),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_kg_chunks_embedding ON kg_chunks USING hnsw (embedding vector_cosine_ops);

-- 图谱→向量同步 outbox (8.2 一致性设计)
CREATE TABLE kg_outbox (
  id BIGSERIAL PRIMARY KEY,
  node_id VARCHAR(128) NOT NULL,
  op VARCHAR(16) NOT NULL,               -- upsert / delete
  processed BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 低置信三元组待审核 (V4 流水线 / V3 5.2)
CREATE TABLE staging_triples (
  id BIGSERIAL PRIMARY KEY,
  head TEXT NOT NULL,
  relation VARCHAR(32) NOT NULL,
  tail TEXT NOT NULL,
  confidence REAL,
  source TEXT,
  status VARCHAR(16) NOT NULL DEFAULT 'pending',  -- pending / approved / rejected
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 技能实体对齐缓存 (V3 6.0)
CREATE TABLE skill_alignments (
  raw_name TEXT PRIMARY KEY,
  node_id VARCHAR(128),                  -- NULL = 未命中
  method VARCHAR(16) NOT NULL,           -- exact / alias / vector / miss
  score REAL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============ 简历与 PII ============
CREATE TABLE resumes (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  raw_encrypted BYTEA NOT NULL,          -- pgcrypto 加密原文
  structured JSONB NOT NULL,             -- 教育/经历/项目/技能(已脱敏)
  embedding vector(1024),                -- 脱敏后文本计算
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE pii_mappings (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  mapping_encrypted BYTEA NOT NULL,      -- 占位符→原值映射, 加密
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============ 岗位与推送 ============
CREATE TABLE jobs (
  id BIGSERIAL PRIMARY KEY,
  node_id VARCHAR(128) UNIQUE,           -- 对应图谱 job 节点
  title TEXT NOT NULL,
  company TEXT,
  city TEXT,
  requires_raw TEXT[],
  jd_snapshot TEXT,
  embedding vector(1024),
  source VARCHAR(32) NOT NULL DEFAULT 'seed',   -- seed / scraped
  released BOOLEAN NOT NULL DEFAULT TRUE,       -- FALSE = Mock 注水池未释放 (V3 6.x)
  fetched_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE push_tasks (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  job_id BIGINT REFERENCES jobs(id),
  status VARCHAR(16) NOT NULL DEFAULT 'pending', -- pending / sent / failed
  retry_count INT NOT NULL DEFAULT 0,
  error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE notifications (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  type VARCHAR(16) NOT NULL,             -- job_push / guide / system
  payload JSONB NOT NULL,
  read BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_user_unread ON notifications(user_id) WHERE NOT read;

-- ============ 可观测性与评估 ============
CREATE TABLE turn_traces (
  id BIGSERIAL PRIMARY KEY,
  trace_id VARCHAR(64) NOT NULL,
  conversation_id BIGINT,
  node VARCHAR(32) NOT NULL,
  snapshot JSONB,
  duration_ms INT,
  tokens_in INT, tokens_out INT, tokens_cached INT,
  llm_calls INT NOT NULL DEFAULT 0,
  degraded BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_turn_traces_trace ON turn_traces(trace_id);

CREATE TABLE tool_calls (
  id BIGSERIAL PRIMARY KEY,
  trace_id VARCHAR(64) NOT NULL,
  agent VARCHAR(32) NOT NULL,
  tool VARCHAR(32) NOT NULL,
  args_digest TEXT,
  status VARCHAR(16) NOT NULL,
  side_effect VARCHAR(4) NOT NULL,       -- L0 / L1 / L2
  duration_ms INT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE llm_usage (
  id BIGSERIAL PRIMARY KEY,
  trace_id VARCHAR(64),
  purpose VARCHAR(16) NOT NULL,          -- chat/router/expert/summary/extract/judge/embed
  model VARCHAR(64) NOT NULL,
  tokens_in INT, tokens_out INT, tokens_cached INT,
  duration_ms INT,
  status VARCHAR(16) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_llm_usage_day ON llm_usage(created_at, purpose);

CREATE TABLE eval_runs (
  id BIGSERIAL PRIMARY KEY,
  git_sha VARCHAR(40),
  mode VARCHAR(32) NOT NULL,             -- vector_only / fused
  model_config JSONB,
  metrics JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
