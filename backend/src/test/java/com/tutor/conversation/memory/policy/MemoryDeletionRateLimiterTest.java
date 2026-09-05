package com.tutor.conversation.memory.policy;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryDeletionRateLimiterTest {
    @Test
    void limitsManualRetryPerUserWithoutAffectingAnotherUser() {
        MemoryDeletionRateLimiter limiter = new MemoryDeletionRateLimiter(2);

        assertThat(limiter.tryAcquire(7L)).isTrue();
        assertThat(limiter.tryAcquire(7L)).isTrue();
        assertThat(limiter.tryAcquire(7L)).isFalse();
        assertThat(limiter.tryAcquire(8L)).isTrue();
    }

    @Test
    void rejectsNonPositiveLimit() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new MemoryDeletionRateLimiter(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usesAtomicDatabaseWindowForMultiInstanceLimit() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(7L)))
                .thenReturn(1, 2, 3);
        MemoryDeletionRateLimiter limiter = new MemoryDeletionRateLimiter(jdbc, 2);

        assertThat(limiter.tryAcquire(7L)).isTrue();
        assertThat(limiter.tryAcquire(7L)).isTrue();
        assertThat(limiter.tryAcquire(7L)).isFalse();
    }

    @Test
    void failsClosedWhenDatabaseWindowCannotBeUpdated() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(7L)))
                .thenThrow(new IllegalStateException("database unavailable"));
        MemoryDeletionRateLimiter limiter = new MemoryDeletionRateLimiter(jdbc, 2);

        assertThat(limiter.tryAcquire(7L)).isFalse();
    }
}
