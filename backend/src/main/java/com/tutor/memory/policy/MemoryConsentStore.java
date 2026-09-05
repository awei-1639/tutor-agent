package com.tutor.memory.policy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL boundary for external-memory consent and generation fencing. */
@Repository
public class MemoryConsentStore {
    private final JdbcTemplate jdbc;

    public MemoryConsentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean enabledFor(long userId) {
        Boolean enabled = jdbc.queryForObject(
                "SELECT external_memory_enabled FROM users WHERE id=?", Boolean.class, userId);
        return Boolean.TRUE.equals(enabled);
    }

    public long currentGeneration(long userId) {
        Long generation = jdbc.queryForObject(
                "SELECT memory_generation FROM users WHERE id=?", Long.class, userId);
        return generation == null ? 0L : generation;
    }

    public int setEnabled(long userId, boolean enabled) {
        return jdbc.update("""
                UPDATE users
                SET external_memory_enabled=?,
                    memory_generation = CASE WHEN ? AND NOT external_memory_enabled
                                             THEN memory_generation + 1 ELSE memory_generation END
                WHERE id=?
                """, enabled, enabled, userId);
    }

    public int incrementGeneration(long userId) {
        return jdbc.update("UPDATE users SET memory_generation=memory_generation+1 WHERE id=?", userId);
    }
}
