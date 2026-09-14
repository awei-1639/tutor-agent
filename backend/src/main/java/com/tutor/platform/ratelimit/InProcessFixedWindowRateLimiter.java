package com.tutor.platform.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进程内固定窗口限流：滚动窗口 (自窗口内首次请求起算)，语义与 {@link DatabaseFixedWindowRateLimiter} 一致。
 * 用于单实例部署、单元测试，以及共享窗口不可用时的兜底——即所有拿不到共享窗口的场景。
 *
 * <p>刻意不标注 {@code @Component}：{@link FixedWindowRateLimiter} 的注入点必须唯一，
 * 多注册一个 bean 会让所有按类型注入的调用方直接启动失败。
 */
public class InProcessFixedWindowRateLimiter implements FixedWindowRateLimiter {
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String scope, long subjectId, int limit, long windowSeconds) {
        if (limit < 1) throw new IllegalArgumentException("rate limit must be positive");
        if (windowSeconds < 1) throw new IllegalArgumentException("window must be positive");
        long now = System.currentTimeMillis();
        long windowMs = windowSeconds * 1000L;
        AtomicBoolean allowed = new AtomicBoolean();
        windows.compute(scope + ":" + subjectId, (key, previous) -> {
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
