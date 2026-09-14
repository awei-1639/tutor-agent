package com.tutor.conversation.memory.policy;

import com.tutor.platform.ratelimit.FixedWindowRateLimiter;
import com.tutor.platform.ratelimit.InProcessFixedWindowRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 限制用户手动重排队远程删除；生产环境使用 PostgreSQL 原子窗口，多实例共享限额。
 *
 * <p>与共享 {@link FixedWindowRateLimiter} 的语义差异是刻意的：远程删除成本高且不可逆，
 * 因此窗口存储不可用时 <b>拒绝</b> 重试 (fail-closed)，而不是回退到进程内窗口放行。
 */
@Component
public class MemoryDeletionRateLimiter {
    private static final String SCOPE = "memory_delete_retry";
    private static final long WINDOW_SECONDS = 60L;
    private static final Logger log = LoggerFactory.getLogger(MemoryDeletionRateLimiter.class);

    private final JdbcTemplate jdbc;
    private final int maxRequestsPerMinute;
    private final FixedWindowRateLimiter localFallback = new InProcessFixedWindowRateLimiter();

    public MemoryDeletionRateLimiter() {
        this(null, 3);
    }

    public MemoryDeletionRateLimiter(
            @Value("${memory.sync.retry-limit-per-minute:3}") int maxRequestsPerMinute) {
        this(null, maxRequestsPerMinute);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MemoryDeletionRateLimiter(JdbcTemplate jdbc,
                                     @Value("${memory.sync.retry-limit-per-minute:3}") int maxRequestsPerMinute) {
        this(jdbc, maxRequestsPerMinute, true);
    }

    private MemoryDeletionRateLimiter(JdbcTemplate jdbc, int maxRequestsPerMinute, boolean ignored) {
        if (maxRequestsPerMinute < 1) {
            throw new IllegalArgumentException("memory deletion retry limit must be positive");
        }
        this.jdbc = jdbc;
        this.maxRequestsPerMinute = maxRequestsPerMinute;
    }

    public boolean tryAcquire(long userId) {
        if (jdbc != null) return tryAcquireShared(userId);
        return localFallback.tryAcquire(SCOPE, userId, maxRequestsPerMinute, WINDOW_SECONDS);
    }

    private boolean tryAcquireShared(long userId) {
        try {
            Integer count = jdbc.queryForObject("""
                    INSERT INTO memory_retry_rate_limits (user_id, window_started_at, request_count)
                    VALUES (?, now(), 1)
                    ON CONFLICT (user_id) DO UPDATE SET
                        window_started_at = CASE
                            WHEN memory_retry_rate_limits.window_started_at <= now() - interval '1 minute'
                                THEN now()
                            ELSE memory_retry_rate_limits.window_started_at
                        END,
                        request_count = CASE
                            WHEN memory_retry_rate_limits.window_started_at <= now() - interval '1 minute'
                                THEN 1
                            ELSE memory_retry_rate_limits.request_count + 1
                        END
                    RETURNING request_count
                    """, Integer.class, userId);
            return count != null && count <= maxRequestsPerMinute;
        } catch (RuntimeException e) {
            // 限流存储不可用时拒绝昂贵的远程重试；不影响普通记忆读取和聊天。
            log.warn("远程记忆删除限流存储不可用，拒绝手动重试 user={}: {}", userId, e.getMessage());
            return false;
        }
    }
}
