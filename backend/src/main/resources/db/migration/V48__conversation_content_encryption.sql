-- 对话文本属于用户数据，静态存储时不得保留明文。
-- 应用使用 pgcrypto 写入密文，并保留旧文本列作为经过掩码、可安全检索的兼容投影。
ALTER TABLE messages ADD COLUMN IF NOT EXISTS content_encrypted BYTEA;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS summary_encrypted BYTEA;

COMMENT ON COLUMN messages.content_encrypted IS 'pgcrypto-encrypted original message; content is a masked compatibility projection';
COMMENT ON COLUMN conversations.summary_encrypted IS 'pgcrypto-encrypted rolling summary; summary is a masked compatibility projection';
