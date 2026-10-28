package com.tutor.platform.ratelimit;

/**
 * 限流拒绝。携带 {@code Retry-After} 秒数，避免客户端盲猜退避时间。
 *
 * <p>与 {@code ResponseStatusException(429)} 的区别仅在于是否带得出冷却时长：
 * 后者由全局异常处理按默认值补齐，前者用于能算出窗口剩余时间的场景。
 */
public class RateLimitedException extends RuntimeException {
    private final long retryAfterSeconds;

    public RateLimitedException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
