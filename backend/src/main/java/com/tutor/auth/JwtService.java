package com.tutor.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.time.Duration;

/**
 * JWT 工具 (Phase 4 V4 4.x): HS256 + secret 来自 env JWT_SECRET。
 * 默认 dev secret fallback (开发环境, 生产必须从 env 注入 ≥32 字节 secret)。
 */
@Component
public class JwtService {
    private final SecretKey key;
    private static final long ACCESS_TTL_MS = Duration.ofMinutes(15).toMillis();

    public JwtService(@Value("${tutor.jwt.secret:}") String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET must be configured with at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 签发 token: subject=userId, claims={name} */
    public String issue(long userId, String name) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("name", name == null ? "" : name)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ACCESS_TTL_MS))
                .signWith(key)
                .compact();
    }

    /** 解析 token: 失败返回 null (静默降级) */
    public Long parse(String token) {
        try {
            String sub = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload().getSubject();
            return Long.valueOf(sub);
        } catch (Exception e) {
            return null;
        }
    }
}
