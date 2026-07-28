-- Phase 4 真实多用户认证 (V4 4.x)
-- 已有 users 表扩展字段 + 唯一索引 + 同步 BIGSERIAL sequence
ALTER TABLE users ADD COLUMN IF NOT EXISTS email VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS name VARCHAR(128);
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email ON users(LOWER(email));
-- 同步序列避免 INSERT 时 nextval 撞已存在 id (历史 dev 数据)
SELECT setval('users_id_seq', GREATEST((SELECT COALESCE(MAX(id), 1) FROM users), 1));