-- Episode 摘要属于用户长期记忆，静态存储时只保留脱敏投影和 pgcrypto 密文。
ALTER TABLE episodes ADD COLUMN IF NOT EXISTS summary_encrypted BYTEA;

COMMENT ON COLUMN episodes.summary_encrypted IS 'pgcrypto 加密的 Episode 原始摘要；summary 为脱敏兼容投影';
