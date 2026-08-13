package com.tutor.auth;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Base64;

/**
 * 注册/登录服务 (Phase 4 V4 4.x): BCrypt 哈希, email 唯一索引, dev fallback 保留.
 * 现状依赖 spring-security-crypto (随 spring-boot-starter-security 间接引入? 不, 需 pom 加).
 */
@Service
public class AuthService {
    private final JdbcTemplate jdbc;
    private final JwtService jwt;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public AuthService(JdbcTemplate jdbc, JwtService jwt) {
        this.jdbc = jdbc;
        this.jwt = jwt;
    }

    public record AuthResult(long userId, String token, String refreshToken, String name, String role) {}

    /** 注册: email 必填且唯一, password ≥6 字符 */
    public AuthResult register(String email, String password, String name) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email 不能为空");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("password ≥6 字符");
        }
        String normalizedEmail = email.trim().toLowerCase();
        // 查重
        Integer existing = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE LOWER(email)=?",
                Integer.class, normalizedEmail);
        if (existing != null && existing > 0) {
            throw new IllegalArgumentException("邮箱已注册");
        }
        String hash = encoder.encode(password);
        long id = jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, name) VALUES (?,?,?) RETURNING id",
                Long.class, normalizedEmail, hash, name == null ? "" : name.trim());
        return issueSession(id, name, "USER");
    }

    /** 登录: 校验密码, 失败抛 */
    public AuthResult login(String email, String password) {
        if (email == null || password == null) {
            throw new IllegalArgumentException("email/password 不能为空");
        }
        String normalizedEmail = email.trim().toLowerCase();
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT id, password_hash, name, role, disabled_at, deleted_at FROM users WHERE LOWER(email)=?",
                    normalizedEmail);
            if (row.get("deleted_at") != null) {
                throw new IllegalArgumentException("账号已删除");
            }
            if (row.get("disabled_at") != null) {
                throw new IllegalArgumentException("账号已被禁用");
            }
            String stored = (String) row.get("password_hash");
            if (stored == null || !encoder.matches(password, stored)) {
                throw new IllegalArgumentException("邮箱或密码错误");
            }
            long id = ((Number) row.get("id")).longValue();
            String name = (String) row.get("name");
            return issueSession(id, name, String.valueOf(row.getOrDefault("role", "USER")));
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("邮箱或密码错误");
        }
    }

    @Transactional
    public AuthResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("refresh token 无效");
        }
        String hash = hash(refreshToken);
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("""
                    SELECT r.user_id, u.name, u.role FROM refresh_tokens r
                    JOIN users u ON u.id = r.user_id
                    WHERE r.token_hash=? AND r.revoked_at IS NULL AND r.expires_at > now()
                      AND u.disabled_at IS NULL AND u.deleted_at IS NULL
                    """, hash);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("refresh token 无效或已过期");
        }
        int consumed = jdbc.update("""
                UPDATE refresh_tokens SET revoked_at=now()
                WHERE token_hash=? AND revoked_at IS NULL AND expires_at > now()
                """, hash);
        if (consumed != 1) {
            // Another concurrent request consumed this token after the read. Do not mint a second session.
            throw new IllegalArgumentException("refresh token 无效或已过期");
        }
        return issueSession(((Number) row.get("user_id")).longValue(), (String) row.get("name"),
                String.valueOf(row.getOrDefault("role", "USER")));
    }

    public void revokeRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) return;
        jdbc.update("UPDATE refresh_tokens SET revoked_at=now() WHERE token_hash=?", hash(refreshToken));
    }

    @Scheduled(cron = "0 15 3 * * *")
    public void purgeRefreshTokens() {
        jdbc.update("DELETE FROM refresh_tokens WHERE expires_at < now() OR revoked_at < now() - INTERVAL '7 days'");
    }

    private AuthResult issueSession(long userId, String name, String role) {
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Timestamp expiresAt = Timestamp.from(Instant.now().plus(Duration.ofDays(30)));
        jdbc.update("INSERT INTO refresh_tokens (token_hash, user_id, expires_at) VALUES (?,?,?)",
                hash(refresh), userId, expiresAt);
        return new AuthResult(userId, jwt.issue(userId, name), refresh, name, role);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法生成 token 摘要", e);
        }
    }
}
