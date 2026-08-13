package com.tutor.interview;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterviewRateLimiterTest {
    @Test
    void limitsOpenAndAnswerBucketsIndependentlyPerUser() {
        InterviewRateLimiter limiter = new InterviewRateLimiter(2, 3);
        assertThat(limiter.tryAcquireOpen(1L)).isTrue();
        assertThat(limiter.tryAcquireOpen(1L)).isTrue();
        assertThat(limiter.tryAcquireOpen(1L)).isFalse();
        assertThat(limiter.tryAcquireOpen(2L)).isTrue();
        assertThat(limiter.tryAcquireAnswer(1L)).isTrue();
        assertThat(limiter.tryAcquireAnswer(1L)).isTrue();
        assertThat(limiter.tryAcquireAnswer(1L)).isTrue();
        assertThat(limiter.tryAcquireAnswer(1L)).isFalse();
    }

    @Test
    void rejectsNonPositiveConfiguration() {
        assertThatThrownBy(() -> new InterviewRateLimiter(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InterviewRateLimiter(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
