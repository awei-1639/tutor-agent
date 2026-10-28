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

    /**
     * 只读查询当前窗口已发生的次数，不消耗额度。
     *
     * <p>为「只对失败计次」的模型服务：调用方需要在真正执行昂贵操作（如 BCrypt 校验）
     * 之前判断是否已被锁定，此时不能顺手计次——否则成功请求也会占用失败配额。
     *
     * @return 窗口内已计次数；窗口已过期或无记录返回 0
     */
    int peek(String scope, long subjectId, long windowSeconds);
}
