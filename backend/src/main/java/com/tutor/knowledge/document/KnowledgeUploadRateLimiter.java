package com.tutor.knowledge.document;

import com.tutor.ratelimit.FixedWindowRateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按管理员划分的固定窗口防护，保护昂贵的解析和 embedding 负载。
 * 注入共享 {@link FixedWindowRateLimiter} 时多实例共享窗口；仅传入限额的构造器
 * 保留进程内窗口，供单实例与单元测试使用。
 */
@Component
public class KnowledgeUploadRateLimiter {
    private static final String SCOPE = "knowledge_upload";
    private static final long WINDOW_SECONDS = 3600L;
    private record Window(long startedAtSecond, int count) {}
    private final ConcurrentHashMap<Long, Window> windows = new ConcurrentHashMap<>();
    private final int limitPerHour;
    private final FixedWindowRateLimiter shared;

    public KnowledgeUploadRateLimiter(@Value("${knowledge.upload.max-per-hour:20}") int limitPerHour) {
        this(limitPerHour, null);
    }

    @Autowired
    public KnowledgeUploadRateLimiter(@Value("${knowledge.upload.max-per-hour:20}") int limitPerHour,
                                      FixedWindowRateLimiter shared) {
        this.limitPerHour = Math.max(1, limitPerHour);
        this.shared = shared;
    }

    public boolean allow(long adminId) {
        if (shared != null) return shared.tryAcquire(SCOPE, adminId, limitPerHour, WINDOW_SECONDS);
        long start = Instant.now().getEpochSecond() / 3600 * 3600;
        Window window = windows.compute(adminId, (key, old) -> old == null || old.startedAtSecond != start
                ? new Window(start, 1) : new Window(start, old.count + 1));
        return window.count <= limitPerHour;
    }
}
