package com.tutor.auth;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;

/** SQL boundary for credentials, users, and refresh-token rotation. */
@Repository
public class AuthStore {
    private final JdbcTemplate jdbc;

    public AuthStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean emailExists(String normalizedEmail) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM users WHERE LOWER(email)=?",
                Integer.class, normalizedEmail);
        return count != null && count > 0;
    }

    public long insertUser(String normalizedEmail, String passwordHash, String name) {
        Long id = jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, name, tenant_id) VALUES (?,?,?,'default') RETURNING id",
                Long.class, normalizedEmail, passwordHash, name);
        if (id == null) throw new IllegalStateException("用户注册未返回 ID");
        return id;
    }

    public Optional<Map<String, Object>> credentials(String normalizedEmail) {
        try {
            return Optional.of(jdbc.queryForMap(
                    "SELECT id, password_hash, name, role, disabled_at, deleted_at, tenant_id FROM users WHERE LOWER(email)=?",
                    normalizedEmail));
        } catch (EmptyResultDataAccessException error) {
            return Optional.empty();
        }
    }

    public Optional<Map<String, Object>> refreshSession(String tokenHash) {
        try {
            return Optional.of(jdbc.queryForMap("""
                    SELECT r.user_id, u.name, u.role, u.tenant_id FROM refresh_tokens r
                    JOIN users u ON u.id = r.user_id
                    WHERE r.token_hash=? AND r.revoked_at IS NULL AND r.expires_at > now()
                      AND u.disabled_at IS NULL AND u.deleted_at IS NULL
                    """, tokenHash));
        } catch (EmptyResultDataAccessException error) {
            return Optional.empty();
        }
    }

    public boolean consumeRefreshToken(String tokenHash) {
        return jdbc.update("""
                UPDATE refresh_tokens SET revoked_at=now()
                WHERE token_hash=? AND revoked_at IS NULL AND expires_at > now()
                """, tokenHash) == 1;
    }

    public void saveRefreshToken(String tokenHash, long userId, Timestamp expiresAt) {
        jdbc.update("INSERT INTO refresh_tokens (token_hash, user_id, expires_at) VALUES (?,?,?)",
                tokenHash, userId, expiresAt);
    }

    public void revokeRefreshToken(String tokenHash) {
        jdbc.update("UPDATE refresh_tokens SET revoked_at=now() WHERE token_hash=?", tokenHash);
    }

    public void purgeRefreshTokens() {
        jdbc.update("DELETE FROM refresh_tokens WHERE expires_at < now() OR revoked_at < now() - INTERVAL '7 days'");
    }
}
