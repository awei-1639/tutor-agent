package com.tutor.push;

import com.tutor.auth.AuthContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 站内消息 (实现设计 8.1: GET /notifications 拉取 + 已读标记) */
@RestController
public class NotificationController {
    private final JdbcTemplate jdbc;

    public NotificationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static long uid() {
        Long u = AuthContext.currentUserId();
        if (u == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "未认证");
        return u;
    }

    @GetMapping("/notifications")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "false") boolean unreadOnly) {
        return jdbc.query("""
                SELECT id, type, payload::text, read, created_at FROM notifications
                WHERE user_id = ? AND (NOT ? OR NOT read)
                ORDER BY id DESC LIMIT 50
                """, (rs, i) -> Map.of(
                "id", rs.getLong(1), "type", rs.getString(2), "payload", rs.getString(3),
                "read", rs.getBoolean(4), "created_at", rs.getTimestamp(5).toInstant().toString()),
                uid(), unreadOnly);
    }

    public record ReadRequest(List<Long> ids) {}

    @PostMapping("/notifications/read")
    public Map<String, Object> markRead(@RequestBody ReadRequest req) {
        if (req.ids() == null || req.ids().isEmpty()) return Map.of("updated", 0);
        String placeholders = String.join(",", req.ids().stream().map(x -> "?").toList());
        Object[] args = new Object[req.ids().size() + 1];
        args[0] = uid();
        for (int i = 0; i < req.ids().size(); i++) args[i + 1] = req.ids().get(i);
        int n = jdbc.update("UPDATE notifications SET read=TRUE WHERE user_id=? AND id IN (" + placeholders + ")", args);
        return Map.of("updated", n);
    }
}
