-- 为情景记忆记录标记加密密钥版本，支持轮换期间使用旧密钥读取并渐进重加密。
ALTER TABLE episodes
    ADD COLUMN IF NOT EXISTS summary_encryption_key_id VARCHAR(64) NOT NULL DEFAULT 'v1';

COMMENT ON COLUMN episodes.summary_encryption_key_id IS 'summary_encrypted 使用的密钥版本标识';
