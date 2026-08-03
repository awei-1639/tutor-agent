-- 画像变更账本：关联对话链路，且按用户时间线高效读取。
ALTER TABLE profile_events ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_profile_events_user_id
    ON profile_events (user_id, id DESC);
