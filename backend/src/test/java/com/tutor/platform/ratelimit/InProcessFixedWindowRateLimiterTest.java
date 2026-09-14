package com.tutor.platform.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InProcessFixedWindowRateLimiterTest {

    @Test
    void limitsEachSubjectIndependently() {
        InProcessFixedWindowRateLimiter limiter = new InProcessFixedWindowRateLimiter();

        assertThat(limiter.tryAcquire("chat", 1L, 2, 60)).isTrue();
        assertThat(limiter.tryAcquire("chat", 1L, 2, 60)).isTrue();
        assertThat(limiter.tryAcquire("chat", 1L, 2, 60)).isFalse();
        assertThat(limiter.tryAcquire("chat", 2L, 2, 60)).isTrue();
    }

    @Test
    void keepsScopesSeparate() {
        InProcessFixedWindowRateLimiter limiter = new InProcessFixedWindowRateLimiter();

        assertThat(limiter.tryAcquire("chat", 1L, 1, 60)).isTrue();
        assertThat(limiter.tryAcquire("chat", 1L, 1, 60)).isFalse();
        assertThat(limiter.tryAcquire("interview_open", 1L, 1, 60)).isTrue();
    }

    @Test
    void rollsOverToANewWindowAfterItElapses() throws InterruptedException {
        InProcessFixedWindowRateLimiter limiter = new InProcessFixedWindowRateLimiter();

        assertThat(limiter.tryAcquire("chat", 1L, 1, 1)).isTrue();
        assertThat(limiter.tryAcquire("chat", 1L, 1, 1)).isFalse();

        Thread.sleep(1_100L);

        assertThat(limiter.tryAcquire("chat", 1L, 1, 1)).isTrue();
    }

    @Test
    void rejectsInvalidArguments() {
        InProcessFixedWindowRateLimiter limiter = new InProcessFixedWindowRateLimiter();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> limiter.tryAcquire("chat", 1L, 0, 60));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> limiter.tryAcquire("chat", 1L, 5, 0));
    }
}
