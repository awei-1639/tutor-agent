package com.tutor.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/** Registration, login, and refresh-token rotation use cases. */
@Service
public class AuthService {
    private final AuthStore store;
    private final JwtService jwt;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public AuthService(AuthStore store, JwtService jwt) {
        this.store = store;
        this.jwt = jwt;
    }

    /** Compatibility constructor retained for database integration tests. */
    public AuthService(JdbcTemplate jdbc, JwtService jwt) {
        this(new AuthStore(jdbc), jwt);
    }

    public record AuthResult(long userId, String token, String refreshToken, String name, String role) {}

    public AuthResult register(String email, String password, String name) {
        if (email == null || email.isBlank()) throw new IllegalArgumentException("email 不能为空");
        if (password == null || password.length() < 6) throw new IllegalArgumentException("password ≥6 字符");
        String normalizedEmail = email.trim().toLowerCase();
        if (store.emailExists(normalizedEmail)) throw new IllegalArgumentException("邮箱已注册");
        String displayName = name == null ? "" : name.trim();
        long id = store.insertUser(normalizedEmail, encoder.encode(password), displayName);
        return issueSession(id, name, "USER", "default");
    }

    public AuthResult login(String email, String password) {
        if (email == null || password == null) throw new IllegalArgumentException("email/password 不能为空");
        String normalizedEmail = email.trim().toLowerCase();
        Map<String, Object> row = store.credentials(normalizedEmail)
                .orElseThrow(() -> new IllegalArgumentException("邮箱或密码错误"));
        if (row.get("deleted_at") != null) throw new IllegalArgumentException("账号已删除");
        if (row.get("disabled_at") != null) throw new IllegalArgumentException("账号已被禁用");
        String stored = (String) row.get("password_hash");
        if (stored == null || !encoder.matches(password, stored)) {
            throw new IllegalArgumentException("邮箱或密码错误");
        }
        return issueSession(((Number) row.get("id")).longValue(), (String) row.get("name"),
                String.valueOf(row.getOrDefault("role", "USER")), (String) row.get("tenant_id"));
    }

    @Transactional
    public AuthResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) throw new IllegalArgumentException("refresh token 无效");
        String tokenHash = hash(refreshToken);
        Map<String, Object> row = store.refreshSession(tokenHash)
                .orElseThrow(() -> new IllegalArgumentException("refresh token 无效或已过期"));
        if (!store.consumeRefreshToken(tokenHash)) {
            throw new IllegalArgumentException("refresh token 无效或已过期");
        }
        return issueSession(((Number) row.get("user_id")).longValue(), (String) row.get("name"),
                String.valueOf(row.getOrDefault("role", "USER")), (String) row.get("tenant_id"));
    }

    public void revokeRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) return;
        store.revokeRefreshToken(hash(refreshToken));
    }

    /** Compatibility entry point; scheduled execution lives in {@link RefreshTokenCleanup}. */
    public void purgeRefreshTokens() {
        store.purgeRefreshTokens();
    }

    private AuthResult issueSession(long userId, String name, String role, String tenantId) {
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        store.saveRefreshToken(hash(refresh), userId, Timestamp.from(Instant.now().plus(Duration.ofDays(30))));
        return new AuthResult(userId, jwt.issue(userId, name, tenantId), refresh, name, role);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法生成 token 摘要", e);
        }
    }
}
