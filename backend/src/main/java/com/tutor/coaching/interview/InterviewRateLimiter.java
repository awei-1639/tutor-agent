package com.tutor.coaching.interview;

import com.tutor.platform.ratelimit.FixedWindowRateLimiter;
import com.tutor.platform.ratelimit.InProcessFixedWindowRateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 面试生命周期限流，开场与作答使用相互独立的窗口。
 * 注入共享 {@link FixedWindowRateLimiter} 时多实例共享同一窗口；
 * 仅传入限额的构造器退回进程内窗口，供单实例与单元测试使用。
 */
@Component
public class InterviewRateLimiter {
    private static final String OPEN_SCOPE = "interview_open";
    private static final String ANSWER_SCOPE = "interview_answer";
    private static final long HOUR_SECONDS = 3600L;
    private static final long MINUTE_SECONDS = 60L;
    private final int maxOpensPerHour;
    private final int maxAnswersPerMinute;
    private final FixedWindowRateLimiter limiter;

    public InterviewRateLimiter(
            @Value("${tutor.interview.open-rate-limit-per-hour:5}") int maxOpensPerHour,
            @Value("${tutor.interview.answer-rate-limit-per-minute:30}") int maxAnswersPerMinute) {
        this(maxOpensPerHour, maxAnswersPerMinute, new InProcessFixedWindowRateLimiter());
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
        this.limiter = shared;
    }

    public boolean tryAcquireOpen(long userId) {
        return limiter.tryAcquire(OPEN_SCOPE, userId, maxOpensPerHour, HOUR_SECONDS);
    }

    public boolean tryAcquireAnswer(long userId) {
        return limiter.tryAcquire(ANSWER_SCOPE, userId, maxAnswersPerMinute, MINUTE_SECONDS);
    }
}
