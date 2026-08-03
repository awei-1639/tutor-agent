package com.tutor.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单实例的用户级聊天限流，优先保护昂贵的 LLM 流式入口。多实例部署时应替换为 Redis 实现。 */
@Component
public class ChatRateLimiter {
    private static final long WINDOW_MS = 60_000L;
    private final int maxRequestsPerMinute;
    private final ConcurrentHashMap<Long, Window> windows = new ConcurrentHashMap<>();

    public ChatRateLimiter(@Value("${tutor.chat.rate-limit-per-minute:20}") int maxRequestsPerMinute) {
        if (maxRequestsPerMinute < 1) throw new IllegalArgumentException("chat rate limit must be positive");
        this.maxRequestsPerMinute = maxRequestsPerMinute;
    }

    public boolean tryAcquire(long userId) {
        long now = System.currentTimeMillis();
        AtomicBoolean allowed = new AtomicBoolean();
        windows.compute(userId, (id, previous) -> {
            if (previous == null || now - previous.windowStartMs >= WINDOW_MS) {
                allowed.set(true);
                return new Window(now, 1);
            }
            if (previous.count >= maxRequestsPerMinute) return previous;
            allowed.set(true);
            return new Window(previous.windowStartMs, previous.count + 1);
        });
        return allowed.get();
    }

    private record Window(long windowStartMs, int count) {}
}
