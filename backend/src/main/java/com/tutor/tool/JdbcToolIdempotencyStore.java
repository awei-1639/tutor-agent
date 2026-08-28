package com.tutor.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.time.Duration;

@Component
public class JdbcToolIdempotencyStore implements ToolIdempotencyStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcToolIdempotencyStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Optional<Object> completed(long userId, String tool, String key) {
        return jdbc.query("SELECT result FROM tool_idempotency WHERE user_id = ? AND tool = ? AND idempotency_key = ? AND status = 'COMPLETED'",
                rs -> rs.next() ? Optional.ofNullable(readResult(rs.getString("result"))) : Optional.empty(), userId, tool, key);
    }

    @Override
    public boolean claim(long userId, String tool, String key) {
        return jdbc.update("""
                INSERT INTO tool_idempotency(user_id, tool, idempotency_key, status)
                VALUES (?, ?, ?, 'RUNNING') ON CONFLICT (user_id, tool, idempotency_key) DO NOTHING
                """, userId, tool, key) == 1;
    }

    @Override
    public void complete(long userId, String tool, String key, Object result) {
        try {
            jdbc.update("""
                    UPDATE tool_idempotency SET status = 'COMPLETED', result = ?::jsonb, updated_at = now()
                    WHERE user_id = ? AND tool = ? AND idempotency_key = ?
                    """, mapper.writeValueAsString(result), userId, tool, key);
        } catch (Exception e) {
            throw new IllegalStateException("工具结果无法保存", e);
        }
    }

    @Override
    public void release(long userId, String tool, String key) {
        jdbc.update("DELETE FROM tool_idempotency WHERE user_id = ? AND tool = ? AND idempotency_key = ? AND status = 'RUNNING'",
                userId, tool, key);
    }

    @Override
    public void reclaimExpired(Duration age) {
        if (age == null || age.isNegative() || age.isZero()) throw new IllegalArgumentException("回收周期必须为正数");
        jdbc.update("DELETE FROM tool_idempotency WHERE status = 'RUNNING' AND updated_at < now() - (? * interval '1 millisecond')",
                age.toMillis());
    }

    private Object readResult(String json) {
        try { return mapper.readValue(json, Object.class); }
        catch (Exception e) { throw new IllegalStateException("已缓存的工具结果无法读取", e); }
    }
}
