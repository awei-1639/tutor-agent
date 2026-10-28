package com.tutor.identity.auth;

import com.tutor.platform.ratelimit.InProcessFixedWindowRateLimiter;
import com.tutor.platform.ratelimit.RateLimitedException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthAttemptLimiterTest {

    @Test
    void ipDimensionBlocksRepeatedAttempts() {
        AuthAttemptLimiter limiter = new AuthAttemptLimiter(2, 10);

        assertThatCode(() -> limiter.acquire(requestFrom("203.0.113.7"), "a@example.com"))
                .doesNotThrowAnyException();
        assertThatCode(() -> limiter.acquire(requestFrom("203.0.113.7"), "a@example.com"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.acquire(requestFrom("203.0.113.7"), "a@example.com"))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void ipDimensionIsPerClient() {
        AuthAttemptLimiter limiter = new AuthAttemptLimiter(1, 10);

        assertThatCode(() -> limiter.acquire(requestFrom("203.0.113.7"), null)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.acquire(requestFrom("198.51.100.9"), null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.acquire(requestFrom("203.0.113.7"), null))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void accountLocksOnlyAfterRepeatedFailures() {
        AuthAttemptLimiter limiter = new AuthAttemptLimiter(100, 2);

        limiter.recordFailure("victim@example.com");
        limiter.recordFailure("victim@example.com");

        assertThatThrownBy(() -> limiter.acquire(requestFrom("203.0.113.7"), "victim@example.com"))
                .isInstanceOf(RateLimitedException.class);
        // 其他账号不受牵连
        assertThatCode(() -> limiter.acquire(requestFrom("203.0.113.7"), "other@example.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void successfulAttemptsDoNotConsumeTheAccountFailureBudget() {
        AuthAttemptLimiter limiter = new AuthAttemptLimiter(100, 3);

        for (int i = 0; i < 10; i++) {
            limiter.acquire(requestFrom("203.0.113.7"), "owner@example.com");
        }

        assertThatCode(() -> limiter.acquire(requestFrom("203.0.113.7"), "owner@example.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void forwardedForIsIgnoredUnlessExplicitlyTrusted() {
        AuthAttemptLimiter untrusted = new AuthAttemptLimiter(
                new InProcessFixedWindowRateLimiter(), 1, 10, false);
        assertThatCode(() -> untrusted.acquire(requestFrom("127.0.0.1", "1.1.1.1"), null))
                .doesNotThrowAnyException();
        // 伪造不同的 X-Forwarded-For 仍落在同一个 remoteAddr 桶里
        assertThatThrownBy(() -> untrusted.acquire(requestFrom("127.0.0.1", "2.2.2.2"), null))
                .isInstanceOf(RateLimitedException.class);

        AuthAttemptLimiter trusted = new AuthAttemptLimiter(
                new InProcessFixedWindowRateLimiter(), 1, 10, true);
        assertThatCode(() -> trusted.acquire(requestFrom("127.0.0.1", "1.1.1.1"), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> trusted.acquire(requestFrom("127.0.0.1", "2.2.2.2"), null))
                .doesNotThrowAnyException();
    }

    @Test
    void digestIsStableAcrossInstances() {
        // 多实例必须落在同一限流行上, 摘要不能依赖进程内状态
        assertThat(AuthAttemptLimiter.digest("user@example.com"))
                .isEqualTo(AuthAttemptLimiter.digest("user@example.com"));
        assertThat(AuthAttemptLimiter.digest("user@example.com"))
                .isNotEqualTo(AuthAttemptLimiter.digest("other@example.com"));
    }

    private static HttpServletRequest requestFrom(String remoteAddr) {
        return requestFrom(remoteAddr, null);
    }

    private static HttpServletRequest requestFrom(String remoteAddr, String forwardedFor) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        when(request.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        return request;
    }
}
