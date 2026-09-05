package com.tutor.platform.ratelimit;

/**
 * 固定窗口限流的统一入口。默认实现由 PostgreSQL 提供多实例共享窗口，
 * 数据库不可用时回退到进程内窗口以保证单实例仍受保护。
 * 多实例部署可替换为 Redis 实现而不改调用方。
 */
public interface FixedWindowRateLimiter {
    /**
     * @param scope     限流用途，如 "chat"、"interview_open"
     * @param subjectId 限流主体 (通常是 userId)
     * @param limit     窗口内允许的最大请求数
     * @param windowSeconds 窗口长度 (秒)
     * @return 是否放行
     */
    boolean tryAcquire(String scope, long subjectId, int limit, long windowSeconds);
}
