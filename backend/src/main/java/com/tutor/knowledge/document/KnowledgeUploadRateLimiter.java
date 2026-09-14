package com.tutor.knowledge.document;

import com.tutor.platform.ratelimit.FixedWindowRateLimiter;
import com.tutor.platform.ratelimit.InProcessFixedWindowRateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 按管理员划分的固定窗口防护，保护昂贵的解析和 embedding 负载。
 * 注入共享 {@link FixedWindowRateLimiter} 时多实例共享同一窗口；仅传入限额的构造器
 * 退回进程内窗口，供单实例与单元测试使用。
 */
@Component
public class KnowledgeUploadRateLimiter {
    private static final String SCOPE = "knowledge_upload";
    private static final long WINDOW_SECONDS = 3600L;
    private final int limitPerHour;
    private final FixedWindowRateLimiter limiter;

    public KnowledgeUploadRateLimiter(@Value("${knowledge.upload.max-per-hour:20}") int limitPerHour) {
        this(limitPerHour, new InProcessFixedWindowRateLimiter());
    }

    @Autowired
    public KnowledgeUploadRateLimiter(@Value("${knowledge.upload.max-per-hour:20}") int limitPerHour,
                                      FixedWindowRateLimiter shared) {
        this.limitPerHour = Math.max(1, limitPerHour);
        this.limiter = shared;
    }

    public boolean allow(long adminId) {
        return limiter.tryAcquire(SCOPE, adminId, limitPerHour, WINDOW_SECONDS);
    }
}
