package com.tutor.conversation.chat.support;

import com.tutor.platform.ratelimit.FixedWindowRateLimiter;
import com.tutor.platform.ratelimit.InProcessFixedWindowRateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 用户级聊天限流，优先保护昂贵的 LLM 流式入口。
 * 注入共享 {@link FixedWindowRateLimiter} 时多实例共享同一窗口；
 * 仅传入限额的构造器退回进程内窗口，供单实例与单元测试使用。
 */
@Component
public class ChatRateLimiter {
    private static final String SCOPE = "chat";
    private static final long WINDOW_SECONDS = 60L;
    private final int maxRequestsPerMinute;
    private final FixedWindowRateLimiter limiter;

    public ChatRateLimiter(@Value("${tutor.chat.rate-limit-per-minute:20}") int maxRequestsPerMinute) {
        this(maxRequestsPerMinute, new InProcessFixedWindowRateLimiter());
    }

    @Autowired
    public ChatRateLimiter(@Value("${tutor.chat.rate-limit-per-minute:20}") int maxRequestsPerMinute,
                           FixedWindowRateLimiter shared) {
        if (maxRequestsPerMinute < 1) throw new IllegalArgumentException("chat rate limit must be positive");
        this.maxRequestsPerMinute = maxRequestsPerMinute;
        this.limiter = shared;
    }

    public boolean tryAcquire(long userId) {
        return limiter.tryAcquire(SCOPE, userId, maxRequestsPerMinute, WINDOW_SECONDS);
    }
}
