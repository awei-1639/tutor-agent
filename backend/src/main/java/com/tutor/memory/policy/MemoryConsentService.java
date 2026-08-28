package com.tutor.memory.policy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MemoryConsentService {
    private final JdbcTemplate jdbc;

    public MemoryConsentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean enabledFor(long userId) {
        Boolean enabled = jdbc.queryForObject(
                "SELECT external_memory_enabled FROM users WHERE id=?", Boolean.class, userId);
        return Boolean.TRUE.equals(enabled);
    }

    public long currentGeneration(long userId) {
        Long generation = jdbc.queryForObject("SELECT memory_generation FROM users WHERE id=?", Long.class, userId);
        return generation == null ? 0L : generation;
    }

    public void setEnabled(long userId, boolean enabled) {
        // 重新启用会开启新的代际，避免旧的延迟远端删除任务清除用户重新授权后创建的记忆。
        jdbc.update("""
                UPDATE users
                SET external_memory_enabled=?,
                    memory_generation = CASE WHEN ? AND NOT external_memory_enabled
                                             THEN memory_generation + 1 ELSE memory_generation END
                WHERE id=?
                """, enabled, enabled, userId);
    }

    /** 在本地删除前使所有已入队的记忆任务失效。 */
    public void invalidateMemoryGeneration(long userId) {
        jdbc.update("UPDATE users SET memory_generation=memory_generation+1 WHERE id=?", userId);
    }
}
