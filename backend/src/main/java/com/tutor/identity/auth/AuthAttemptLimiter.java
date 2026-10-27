package com.tutor.identity.auth;

import com.tutor.platform.ratelimit.FixedWindowRateLimiter;
import com.tutor.platform.ratelimit.RateLimitedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * 认证入口限流：/auth/login、/auth/register、/auth/dev-login 是全站唯一
 * 「无认证 + 高成本计算（BCrypt）」的组合，不加限制既可以被无限撞库，
 * 也可以用几百个并发把 CPU 全部烧在密码哈希上。
 *
 * <p>双维度，语义刻意不同：
 * <ul>
 *   <li><b>IP 维度</b>：所有尝试都计次（成功也计）。目的是压制单位时间内的尝试总量，
 *       成功请求本身也应占用入口预算，否则攻击者可用真实账号先登录、再无限重试他人账号。</li>
 *   <li><b>账号维度</b>：只统计失败。正常用户多设备登录、刷新会话不会被逐渐锁死；
 *       达到阈值后在窗口剩余时间内直接拒绝，连 BCrypt 都不做。</li>
 * </ul>
 *
 * <p>限流主体只存 64 位摘要，不落 IP 明文与邮箱明文——限流表是运维可见的，
 * 不该成为账号清单的副本。
 */
@Component
public class AuthAttemptLimiter {
    private static final Logger log = LoggerFactory.getLogger(AuthAttemptLimiter.class);

    private static final String IP_SCOPE = "auth_ip";
    private static final String ACCOUNT_SCOPE = "auth_account_fail";
    private static final long IP_WINDOW_SECONDS = 300L;
    private static final long ACCOUNT_WINDOW_SECONDS = 600L;

    private final FixedWindowRateLimiter limiter;
    private final int ipAttemptLimit;
    private final int accountFailureLimit;
    private final boolean trustForwardedFor;

    @Autowired
    public AuthAttemptLimiter(FixedWindowRateLimiter limiter,
                              @Value("${tutor.auth.ip-attempt-limit:20}") int ipAttemptLimit,
                              @Value("${tutor.auth.account-failure-limit:10}") int accountFailureLimit,
                              @Value("${tutor.auth.trust-forwarded-for:false}") boolean trustForwardedFor) {
        if (ipAttemptLimit < 1) throw new IllegalArgumentException("auth ip attempt limit must be positive");
        if (accountFailureLimit < 1) throw new IllegalArgumentException("auth account failure limit must be positive");
        this.limiter = limiter;
        this.ipAttemptLimit = ipAttemptLimit;
        this.accountFailureLimit = accountFailureLimit;
        this.trustForwardedFor = trustForwardedFor;
    }

    /** 单机与单元测试入口：进程内窗口，不共享。 */
    public AuthAttemptLimiter(int ipAttemptLimit, int accountFailureLimit) {
        this(new com.tutor.platform.ratelimit.InProcessFixedWindowRateLimiter(),
                ipAttemptLimit, accountFailureLimit, false);
    }

    /**
     * 认证入口前置检查：先按 IP 计次，再判断该账号是否处于失败锁定期。
     * 抛 {@link RateLimitedException} 表示本次请求不应继续执行密码校验。
     */
    public void acquire(HttpServletRequest request, String email) {
        String ip = clientIp(request);
        if (!limiter.tryAcquire(IP_SCOPE, digest(ip), ipAttemptLimit, IP_WINDOW_SECONDS)) {
            log.warn("认证入口 IP 限流触发 ip={}", ip);
            throw new RateLimitedException("登录尝试过于频繁，请稍后再试", IP_WINDOW_SECONDS);
        }
        if (email == null || email.isBlank()) return;
        long accountKey = digest(normalize(email));
        if (limiter.peek(ACCOUNT_SCOPE, accountKey, ACCOUNT_WINDOW_SECONDS) >= accountFailureLimit) {
            log.warn("认证入口账号失败锁定命中 accountKey={}", accountKey);
            throw new RateLimitedException("该账号尝试次数过多，请稍后再试", ACCOUNT_WINDOW_SECONDS);
        }
    }

    /** 仅在认证失败时调用。成功登录不消耗账号维度配额。 */
    public void recordFailure(String email) {
        if (email == null || email.isBlank()) return;
        limiter.tryAcquire(ACCOUNT_SCOPE, digest(normalize(email)), accountFailureLimit, ACCOUNT_WINDOW_SECONDS);
    }

    /**
     * 客户端地址。默认只信 {@code remoteAddr}：{@code X-Forwarded-For} 是客户端可伪造的，
     * 无条件采信等于把 IP 限流交给攻击者关闭。反向代理/隧道部署时由
     * {@code AUTH_TRUST_FORWARDED_FOR=true} 显式开启。
     */
    private String clientIp(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                String first = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
                if (!first.isEmpty()) return first;
            }
        }
        String remote = request.getRemoteAddr();
        return (remote == null || remote.isBlank()) ? "unknown" : remote;
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** 稳定 64 位摘要：多实例必须落在同一限流行上，因此不能用随机盐或 identityHashCode。 */
    static long digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(hash, 0, Long.BYTES).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
