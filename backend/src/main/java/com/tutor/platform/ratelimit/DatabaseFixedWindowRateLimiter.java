package com.tutor.platform.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 默认限流实现：PostgreSQL 原子固定窗口，多实例共享同一计数。
 * 数据库不可用时回退到进程内窗口，保证单实例部署与测试仍受保护，且不阻断主流程。
 */
@Component
public class DatabaseFixedWindowRateLimiter implements FixedWindowRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(DatabaseFixedWindowRateLimiter.class);

    private final JdbcTemplate jdbc;
    private final ConcurrentHashMap<String, Window> localFallback = new ConcurrentHashMap<>();

    public DatabaseFixedWindowRateLimiter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryAcquire(String scope, long subjectId, int limit, long windowSeconds) {
        if (limit < 1) throw new IllegalArgumentException("rate limit must be positive");
        if (windowSeconds < 1) throw new IllegalArgumentException("window must be positive");
        try {
            Integer count = jdbc.queryForObject("""
                    INSERT INTO rate_limit_windows (scope, subject_id, window_started_at, request_count)
                    VALUES (?, ?, now(), 1)
                    ON CONFLICT (scope, subject_id) DO UPDATE SET
                        window_started_at = CASE
                            WHEN rate_limit_windows.window_started_at <= now() - make_interval(secs => ?)
                                THEN now()
                            ELSE rate_limit_windows.window_started_at
                        END,
                        request_count = CASE
                            WHEN rate_limit_windows.window_started_at <= now() - make_interval(secs => ?)
                                THEN 1
                            ELSE rate_limit_windows.request_count + 1
                        END
                    RETURNING request_count
                    """, Integer.class, scope, subjectId, (double) windowSeconds, (double) windowSeconds);
            return count != null && count <= limit;
        } catch (RuntimeException e) {
            log.warn("共享限流存储不可用，回退到进程内窗口 scope={} subject={}: {}", scope, subjectId, e.getMessage());
            return tryAcquireLocal(scope, subjectId, limit, windowSeconds * 1000L);
        }
    }

    private boolean tryAcquireLocal(String scope, long subjectId, int limit, long windowMs) {
        long now = System.currentTimeMillis();
        AtomicBoolean allowed = new AtomicBoolean();
        localFallback.compute(scope + ":" + subjectId, (id, previous) -> {
            if (previous == null || now - previous.startedAt() >= windowMs) {
                allowed.set(true);
                return new Window(now, 1);
            }
            if (previous.count() >= limit) return previous;
            allowed.set(true);
            return new Window(previous.startedAt(), previous.count() + 1);
        });
        return allowed.get();
    }

    private record Window(long startedAt, int count) {}
}
