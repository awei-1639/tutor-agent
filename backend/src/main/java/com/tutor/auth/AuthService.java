package com.tutor.auth;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 注册/登录服务 (Phase 4 V4 4.x): BCrypt 哈希, email 唯一索引, dev fallback 保留.
 * 现状依赖 spring-security-crypto (随 spring-boot-starter-security 间接引入? 不, 需 pom 加).
 */
@Service
public class AuthService {
    private final JdbcTemplate jdbc;
    private final JwtService jwt;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(JdbcTemplate jdbc, JwtService jwt) {
        this.jdbc = jdbc;
        this.jwt = jwt;
    }

    public record AuthResult(long userId, String token, String name) {}

    /** 注册: email 必填且唯一, password ≥6 字符 */
    public AuthResult register(String email, String password, String name) {
        if (email == null || email.isBlank()) throw new IllegalArgumentException("email 不能为空");
        if (password == null || password.length() < 6) throw new IllegalArgumentException("password ≥6 字符");
        String normalizedEmail = email.trim().toLowerCase();
        // 查重
        Integer existing = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE LOWER(email)=?",
                Integer.class, normalizedEmail);
        if (existing != null && existing > 0) throw new IllegalArgumentException("邮箱已注册");
        String hash = encoder.encode(password);
        long id = jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, name) VALUES (?,?,?) RETURNING id",
                Long.class, normalizedEmail, hash, name == null ? "" : name.trim());
        return new AuthResult(id, jwt.issue(id, name), name);
    }

    /** 登录: 校验密码, 失败抛 */
    public AuthResult login(String email, String password) {
        if (email == null || password == null) throw new IllegalArgumentException("email/password 不能为空");
        String normalizedEmail = email.trim().toLowerCase();
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT id, password_hash, name FROM users WHERE LOWER(email)=?",
                    normalizedEmail);
            String stored = (String) row.get("password_hash");
            if (stored == null || !encoder.matches(password, stored)) {
                throw new IllegalArgumentException("邮箱或密码错误");
            }
            long id = ((Number) row.get("id")).longValue();
            String name = (String) row.get("name");
            return new AuthResult(id, jwt.issue(id, name), name);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("邮箱或密码错误");
        }
    }
}