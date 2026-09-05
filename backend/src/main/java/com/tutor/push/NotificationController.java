package com.tutor.push;

import com.tutor.identity.auth.AuthContext;
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
    private final NotificationStore notifications;

    public NotificationController(NotificationStore notifications) {
        this.notifications = notifications;
    }

    private static long uid() {
        Long u = AuthContext.currentUserId();
        if (u == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "未认证");
        return u;
    }

    @GetMapping("/notifications")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "false") boolean unreadOnly) {
        return notifications.list(uid(), unreadOnly);
    }

    public record ReadRequest(List<Long> ids) {}

    @PostMapping("/notifications/read")
    public Map<String, Object> markRead(@RequestBody ReadRequest req) {
        return Map.of("updated", notifications.markRead(uid(), req.ids()));
    }
}
