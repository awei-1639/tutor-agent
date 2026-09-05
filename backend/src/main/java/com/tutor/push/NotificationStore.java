package com.tutor.push;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/** Persistence boundary for user notifications. */
@Repository
public class NotificationStore {
    private final JdbcTemplate jdbc;

    public NotificationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> list(long userId, boolean unreadOnly) {
        return jdbc.query("""
                SELECT id, type, payload::text, read, created_at FROM notifications
                WHERE user_id = ? AND (NOT ? OR NOT read)
                ORDER BY id DESC LIMIT 50
                """, (rs, i) -> Map.of(
                "id", rs.getLong(1), "type", rs.getString(2), "payload", rs.getString(3),
                "read", rs.getBoolean(4), "created_at", rs.getTimestamp(5).toInstant().toString()),
                userId, unreadOnly);
    }

    public int markRead(long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        String placeholders = String.join(",", ids.stream().map(x -> "?").toList());
        Object[] args = new Object[ids.size() + 1];
        args[0] = userId;
        for (int i = 0; i < ids.size(); i++) args[i + 1] = ids.get(i);
        return jdbc.update("UPDATE notifications SET read=TRUE WHERE user_id=? AND id IN (" + placeholders + ")", args);
    }

    public void add(long userId, String type, String payload) {
        jdbc.update("INSERT INTO notifications (user_id, type, payload) VALUES (?, ?, ?::jsonb)",
                userId, type, payload);
    }

    public boolean hasUnreadGuide(long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id=? AND type='guide' AND NOT read",
                Integer.class, userId);
        return count != null && count > 0;
    }
}
