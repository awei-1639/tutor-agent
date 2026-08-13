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

    public void setEnabled(long userId, boolean enabled) {
        jdbc.update("UPDATE users SET external_memory_enabled=? WHERE id=?", enabled, userId);
    }

    /** Invalidates every already queued memory job before local deletion. */
    public void invalidateMemoryGeneration(long userId) {
        jdbc.update("UPDATE users SET memory_generation=memory_generation+1 WHERE id=?", userId);
    }
}
