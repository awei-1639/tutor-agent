package com.tutor.interview;

import com.tutor.ratelimit.FixedWindowRateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 面试生命周期限流。注入共享 {@link FixedWindowRateLimiter} 时多实例共享窗口；
 * 仅传入限额的构造器保留进程内窗口，供单实例与单元测试使用。
 */
@Component
public class InterviewRateLimiter {
    private static final String OPEN_SCOPE = "interview_open";
    private static final String ANSWER_SCOPE = "interview_answer";
    private static final long HOUR_MS = 3_600_000L;
    private static final long MINUTE_MS = 60_000L;
    private final int maxOpensPerHour;
    private final int maxAnswersPerMinute;
    private final FixedWindowRateLimiter shared;
    private final ConcurrentHashMap<Long, Window> opens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Window> answers = new ConcurrentHashMap<>();

    public InterviewRateLimiter(
            @Value("${tutor.interview.open-rate-limit-per-hour:5}") int maxOpensPerHour,
            @Value("${tutor.interview.answer-rate-limit-per-minute:30}") int maxAnswersPerMinute) {
        this(maxOpensPerHour, maxAnswersPerMinute, null);
    }

    @Autowired
    public InterviewRateLimiter(
            @Value("${tutor.interview.open-rate-limit-per-hour:5}") int maxOpensPerHour,
            @Value("${tutor.interview.answer-rate-limit-per-minute:30}") int maxAnswersPerMinute,
            FixedWindowRateLimiter shared) {
        if (maxOpensPerHour < 1 || maxAnswersPerMinute < 1) {
            throw new IllegalArgumentException("interview rate limits must be positive");
        }
        this.maxOpensPerHour = maxOpensPerHour;
        this.maxAnswersPerMinute = maxAnswersPerMinute;
        this.shared = shared;
    }

    public boolean tryAcquireOpen(long userId) {
        if (shared != null) return shared.tryAcquire(OPEN_SCOPE, userId, maxOpensPerHour, HOUR_MS / 1000L);
        return tryAcquire(opens, userId, maxOpensPerHour, HOUR_MS);
    }

    public boolean tryAcquireAnswer(long userId) {
        if (shared != null) return shared.tryAcquire(ANSWER_SCOPE, userId, maxAnswersPerMinute, MINUTE_MS / 1000L);
        return tryAcquire(answers, userId, maxAnswersPerMinute, MINUTE_MS);
    }

    private boolean tryAcquire(ConcurrentHashMap<Long, Window> store, long userId, int limit, long windowMs) {
        long now = System.currentTimeMillis();
        AtomicBoolean allowed = new AtomicBoolean();
        store.compute(userId, (id, previous) -> {
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
