package com.tutor.conversation.chat.support;

import com.tutor.conversation.chat.support.ChatRateLimiter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRateLimiterTest {
    @Test
    void limitsEachUserIndependently() {
        ChatRateLimiter limiter = new ChatRateLimiter(2);

        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isFalse();
        assertThat(limiter.tryAcquire(2L)).isTrue();
    }
}
