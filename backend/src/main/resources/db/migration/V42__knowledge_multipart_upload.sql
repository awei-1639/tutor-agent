ALTER TABLE knowledge_upload_sessions ADD COLUMN IF NOT EXISTS upload_id TEXT;
ALTER TABLE knowledge_upload_sessions ADD COLUMN IF NOT EXISTS part_size BIGINT;
