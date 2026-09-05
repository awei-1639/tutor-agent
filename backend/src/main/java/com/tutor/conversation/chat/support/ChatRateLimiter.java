package com.tutor.conversation.chat.support;

import com.tutor.ratelimit.FixedWindowRateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 用户级聊天限流，优先保护昂贵的 LLM 流式入口。
 * 注入共享 {@link FixedWindowRateLimiter} 时多实例共享同一窗口；
 * 仅传入限额的构造器保留进程内窗口，供单实例与单元测试使用。
 */
@Component
public class ChatRateLimiter {
    private static final String SCOPE = "chat";
    private static final long WINDOW_MS = 60_000L;
    private static final long WINDOW_SECONDS = 60L;
    private final int maxRequestsPerMinute;
    private final FixedWindowRateLimiter shared;
    private final ConcurrentHashMap<Long, Window> windows = new ConcurrentHashMap<>();

    public ChatRateLimiter(@Value("${tutor.chat.rate-limit-per-minute:20}") int maxRequestsPerMinute) {
        this(maxRequestsPerMinute, null);
    }

    @Autowired
    public ChatRateLimiter(@Value("${tutor.chat.rate-limit-per-minute:20}") int maxRequestsPerMinute,
                           FixedWindowRateLimiter shared) {
        if (maxRequestsPerMinute < 1) throw new IllegalArgumentException("chat rate limit must be positive");
        this.maxRequestsPerMinute = maxRequestsPerMinute;
        this.shared = shared;
    }

    public boolean tryAcquire(long userId) {
        if (shared != null) return shared.tryAcquire(SCOPE, userId, maxRequestsPerMinute, WINDOW_SECONDS);
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
