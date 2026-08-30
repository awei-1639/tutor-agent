-- 语义事实层：从已脱敏的 Episode 源窗口中抽取的原子短事实（目标/偏好/技能等）。
-- 事实不可变追加，冲突不就地改写，而是插入新事实并把旧事实软失效（superseded 指针），
-- 参考 mem0 的 ADD/UPDATE 与 Graphiti 的事实失效思想，但消解是确定性的（同类目 + 词汇重叠阈值）。
-- source_episode_id 使用 SET NULL：保留清理（过期/超限裁剪）不应连带杀死高价值事实；
-- 用户显式删除 Episode 时由应用层级联删除其来源事实。
CREATE TABLE IF NOT EXISTS user_facts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    memory_generation BIGINT NOT NULL DEFAULT 0,
    source_episode_id BIGINT REFERENCES episodes(id) ON DELETE SET NULL,
    fact_text TEXT NOT NULL,
    fact_encrypted BYTEA,
    fact_encryption_key_id VARCHAR(16),
    fact_hash VARCHAR(64) NOT NULL,
    category VARCHAR(16) NOT NULL
        CHECK (category IN ('goal', 'preference', 'skill', 'constraint', 'background')),
    confidence REAL NOT NULL DEFAULT 0.6 CHECK (confidence >= 0 AND confidence <= 1),
    status VARCHAR(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'superseded')),
    superseded_by BIGINT REFERENCES user_facts(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ DEFAULT (now() + INTERVAL '365 days')
);

COMMENT ON COLUMN user_facts.fact_encrypted IS 'pgcrypto 加密的事实原文；fact_text 为脱敏兼容投影';

-- 幂等键：同用户同 canonical 文本只允许一条 active 事实。
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_facts_hash
    ON user_facts (user_id, fact_hash) WHERE status = 'active';

CREATE INDEX IF NOT EXISTS idx_user_facts_active
    ON user_facts (user_id, status, expires_at) WHERE status = 'active';

CREATE INDEX IF NOT EXISTS idx_user_facts_source
    ON user_facts (source_episode_id) WHERE source_episode_id IS NOT NULL;
