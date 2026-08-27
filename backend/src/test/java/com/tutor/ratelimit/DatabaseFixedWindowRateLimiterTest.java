package com.tutor.ratelimit;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class DatabaseFixedWindowRateLimiterTest {

    @Test
    void allowsUntilLimitThenRejects() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(1, 2, 3);
        DatabaseFixedWindowRateLimiter limiter = new DatabaseFixedWindowRateLimiter(jdbc);

        assertThat(limiter.tryAcquire("chat", 7L, 2, 60)).isTrue();
        assertThat(limiter.tryAcquire("chat", 7L, 2, 60)).isTrue();
        assertThat(limiter.tryAcquire("chat", 7L, 2, 60)).isFalse();
    }

    @Test
    void fallsBackToInProcessWindowWhenDatabaseUnavailable() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));
        DatabaseFixedWindowRateLimiter limiter = new DatabaseFixedWindowRateLimiter(jdbc);

        assertThat(limiter.tryAcquire("chat", 9L, 2, 60)).isTrue();
        assertThat(limiter.tryAcquire("chat", 9L, 2, 60)).isTrue();
        assertThat(limiter.tryAcquire("chat", 9L, 2, 60)).isFalse();
    }

    @Test
    void rejectsInvalidArguments() {
        DatabaseFixedWindowRateLimiter limiter = new DatabaseFixedWindowRateLimiter(Mockito.mock(JdbcTemplate.class));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> limiter.tryAcquire("chat", 1L, 0, 60));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> limiter.tryAcquire("chat", 1L, 5, 0));
    }
}
