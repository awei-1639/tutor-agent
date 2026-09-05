package com.tutor.conversation.memory.local;

import com.tutor.identity.resume.PiiMasker;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** SQL and encryption adapter for semantic facts. */
final class FactPersistenceStore {
    private final JdbcTemplate jdbc;
    private final String encKey;
    private final String encKeyId;
    private final String previousEncKey;
    private final String previousEncKeyId;

    FactPersistenceStore(JdbcTemplate jdbc, String encKey, String encKeyId,
                         String previousEncKey, String previousEncKeyId) {
        this.jdbc = jdbc;
        this.encKey = encKey == null ? "" : encKey;
        this.encKeyId = encKeyId == null || encKeyId.isBlank() ? "v1" : encKeyId.trim();
        this.previousEncKey = previousEncKey == null ? "" : previousEncKey;
        this.previousEncKeyId = previousEncKeyId == null ? "" : previousEncKeyId.trim();
    }

    long insertIfAbsentReturningId(long userId, Long sourceEpisodeId, long memoryGeneration,
                                   String factText, String category, double confidence) {
        String safeCategory = FactPolicy.normalizeCategory(category);
        double safeConfidence = Math.clamp(confidence, 0D, 1D);
        String hash = FactPolicy.hashOf(factText);
        String maskedText = encKey.isBlank() ? factText : PiiMasker.mask(factText).masked();
        boolean fenced = memoryGeneration != Long.MIN_VALUE;
        String fence = fenced ? " WHERE EXISTS (SELECT 1 FROM users WHERE id=? AND memory_generation=?)" : "";
        String sql = encKey.isBlank() ? """
                INSERT INTO user_facts (user_id, source_episode_id, memory_generation,
                                        fact_text, fact_hash, category, confidence)
                SELECT ?,?,?,?,?,?,?%s
                ON CONFLICT (user_id, fact_hash) WHERE status = 'active' DO NOTHING
                RETURNING id
                """.formatted(fence) : """
                INSERT INTO user_facts (user_id, source_episode_id, memory_generation,
                                        fact_text, fact_encrypted, fact_encryption_key_id,
                                        fact_hash, category, confidence)
                SELECT ?,?,?,?,pgp_sym_encrypt(?, ?),?,?,?,?%s
                ON CONFLICT (user_id, fact_hash) WHERE status = 'active' DO NOTHING
                RETURNING id
                """.formatted(fence);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add(sourceEpisodeId);
        args.add(memoryGeneration);
        args.add(maskedText);
        if (encKey.isBlank()) {
            args.add(hash);
            args.add(safeCategory);
            args.add(safeConfidence);
        } else {
            args.add(maskedText);
            args.add(encKey);
            args.add(encKeyId);
            args.add(hash);
            args.add(safeCategory);
            args.add(safeConfidence);
        }
        if (fenced) {
            args.add(userId);
            args.add(memoryGeneration);
        }
        List<Long> ids = jdbc.query(sql, (rs, rowNum) -> rs.getLong(1), args.toArray());
        return ids.isEmpty() ? 0L : ids.getFirst();
    }

    List<FactStore.UserFact> activeByUser(long userId, int limit) {
        String sql = """
                SELECT id, %s, category, confidence, created_at, updated_at
                FROM user_facts
                WHERE user_id = ? AND status = 'active'
                  AND (expires_at IS NULL OR expires_at > now())
                ORDER BY updated_at DESC, id DESC
                LIMIT ?
                """.formatted(factColumn());
        return encKey.isBlank()
                ? jdbc.query(sql, this::mapFact, userId, limit)
                : jdbc.query(sql, this::mapFact, queryArgs(userId, limit));
    }

    List<FactStore.UserFact> activeByUserAndCategory(long userId, String category, int limit) {
        String safeCategory = FactPolicy.normalizeCategory(category);
        String sql = """
                SELECT id, %s, category, confidence, created_at, updated_at
                FROM user_facts
                WHERE user_id = ? AND category = ?
                  AND status = 'active' AND (expires_at IS NULL OR expires_at > now())
                ORDER BY updated_at DESC, id DESC
                LIMIT ?
                """.formatted(factColumn());
        return encKey.isBlank()
                ? jdbc.query(sql, this::mapFact, userId, safeCategory, limit)
                : jdbc.query(sql, this::mapFact, queryArgs(userId, safeCategory, limit));
    }

    boolean markSuperseded(long userId, long factId, long supersededById, long expectedGeneration) {
        if (expectedGeneration == Long.MIN_VALUE) {
            return jdbc.update("""
                    UPDATE user_facts SET status='superseded', superseded_by=?, updated_at=now()
                    WHERE id=? AND user_id=? AND status='active'
                    """, supersededById, factId, userId) > 0;
        }
        return jdbc.update("""
                UPDATE user_facts SET status='superseded', superseded_by=?, updated_at=now()
                WHERE id=? AND user_id=? AND status='active'
                  AND EXISTS (SELECT 1 FROM users WHERE id=? AND memory_generation=?)
                """, supersededById, factId, userId, userId, expectedGeneration) > 0;
    }

    void deleteByUser(long userId) {
        jdbc.update("DELETE FROM user_facts WHERE user_id=?", userId);
    }

    boolean deleteByIdForUser(long id, long userId) {
        return jdbc.update("DELETE FROM user_facts WHERE id=? AND user_id=? AND status='active'", id, userId) > 0;
    }

    int deleteBySourceEpisodeId(long userId, long sourceEpisodeId) {
        return jdbc.update("DELETE FROM user_facts WHERE user_id=? AND source_episode_id=?", userId, sourceEpisodeId);
    }

    private String factColumn() {
        if (encKey.isBlank()) return "fact_text";
        if (!hasPreviousKey()) {
            return """
                    CASE
                        WHEN fact_encrypted IS NOT NULL AND fact_encryption_key_id=?
                            THEN pgp_sym_decrypt(fact_encrypted, ?)
                        ELSE fact_text
                    END
                    """;
        }
        return """
                CASE
                    WHEN fact_encrypted IS NOT NULL AND fact_encryption_key_id=?
                        THEN pgp_sym_decrypt(fact_encrypted, ?)
                    WHEN fact_encrypted IS NOT NULL AND fact_encryption_key_id=?
                        THEN pgp_sym_decrypt(fact_encrypted, ?)
                    ELSE fact_text
                END
                """;
    }

    private Object[] queryArgs(Object... tail) {
        if (encKey.isBlank()) return tail;
        List<Object> args = new ArrayList<>(4 + tail.length);
        args.add(encKeyId);
        args.add(encKey);
        if (hasPreviousKey()) {
            args.add(previousEncKeyId);
            args.add(previousEncKey);
        }
        Collections.addAll(args, tail);
        return args.toArray();
    }

    private boolean hasPreviousKey() {
        return !previousEncKey.isBlank() && !previousEncKeyId.isBlank()
                && !previousEncKeyId.equals(encKeyId);
    }

    private FactStore.UserFact mapFact(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new FactStore.UserFact(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getDouble(4),
                rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant());
    }
}
