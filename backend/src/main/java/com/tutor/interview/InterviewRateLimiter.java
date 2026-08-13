package com.tutor.interview;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-process guard for the expensive interview lifecycle; use Redis for multi-instance deployment. */
@Component
public class InterviewRateLimiter {
    private static final long HOUR_MS = 3_600_000L;
    private static final long MINUTE_MS = 60_000L;
    private final int maxOpensPerHour;
    private final int maxAnswersPerMinute;
    private final ConcurrentHashMap<Long, Window> opens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Window> answers = new ConcurrentHashMap<>();

    public InterviewRateLimiter(
            @Value("${tutor.interview.open-rate-limit-per-hour:5}") int maxOpensPerHour,
            @Value("${tutor.interview.answer-rate-limit-per-minute:30}") int maxAnswersPerMinute) {
        if (maxOpensPerHour < 1 || maxAnswersPerMinute < 1) {
            throw new IllegalArgumentException("interview rate limits must be positive");
        }
        this.maxOpensPerHour = maxOpensPerHour;
        this.maxAnswersPerMinute = maxAnswersPerMinute;
    }

    public boolean tryAcquireOpen(long userId) {
        return tryAcquire(opens, userId, maxOpensPerHour, HOUR_MS);
    }

    public boolean tryAcquireAnswer(long userId) {
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
