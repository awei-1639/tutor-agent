-- 保留单条记忆删除的本地墓碑，用于屏蔽远端副本和延迟同步事件。
CREATE TABLE IF NOT EXISTS episode_memory_tombstones (
    user_id BIGINT NOT NULL REFERENCES users(id),
    memory_id BIGINT NOT NULL,
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, memory_id)
);
