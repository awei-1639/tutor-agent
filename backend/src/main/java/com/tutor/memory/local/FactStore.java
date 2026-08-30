package com.tutor.memory.local;

import com.tutor.resume.PiiMasker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * L2.5 语义事实存储：从已准入的 Episode 源窗口抽取的原子短事实。
 * 事实不可变；冲突消解通过软失效（status='superseded' + superseded_by 指针）完成，
 * 不做就地改写，保证审计与可回滚。加密与密钥轮换沿用 episodes 的 pgcrypto 双列模式。
 */
@Component
public class FactStore {
    public static final Set<String> CATEGORIES =
            Set.of("goal", "preference", "skill", "constraint", "background");

    private final JdbcTemplate jdbc;
    private final String encKey;
    private final String encKeyId;
    private final String previousEncKey;
    private final String previousEncKeyId;

    public FactStore(JdbcTemplate jdbc) {
        this(jdbc, "", "v1", "", "");
    }

    @Autowired
    public FactStore(JdbcTemplate jdbc,
                     @Value("${security.resume-enc-key:}") String encKey,
                     @Value("${security.resume-enc-key-id:v1}") String encKeyId,
                     @Value("${security.resume-enc-previous-key:}") String previousEncKey,
                     @Value("${security.resume-enc-previous-key-id:}") String previousEncKeyId) {
        this.jdbc = jdbc;
        this.encKey = encKey == null ? "" : encKey;
        this.encKeyId = normalizeKeyId(encKeyId, "v1");
        this.previousEncKey = previousEncKey == null ? "" : previousEncKey;
        this.previousEncKeyId = previousEncKeyId == null ? "" : previousEncKeyId.trim();
    }

    public record UserFact(long id, String factText, String category,
                           double confidence, Instant createdAt, Instant updatedAt) {}

    /**
     * 插入一条事实；同用户同 canonical 文本已存在 active 事实时返回 0（幂等）。
     * expectedGeneration 为 {@link Long#MIN_VALUE} 时不做代际围栏（与 EpisodeCommitter 约定一致）。
     */
    public long insertIfAbsentReturningId(long userId, Long sourceEpisodeId, long memoryGeneration,
                                          String factText, String category, double confidence) {
        String safeCategory = normalizeCategory(category);
        double safeConfidence = Math.clamp(confidence, 0D, 1D);
        String hash = hashOf(factText);
        String maskedText = encKey.isBlank() ? factText : PiiMasker.mask(factText).masked();
        boolean fenced = memoryGeneration != Long.MIN_VALUE;
        // 代际 fencing 与 EpisodeCommitter 同语义：用户清除记忆后，在途抽取不得再写入。
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

    /** 用户当前有效事实（画像事实总量小，直接全量加载用于排序）。 */
    public List<UserFact> activeByUser(long userId, int limit) {
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

    /** 消解候选：同用户同类目的有效事实，最新的在前。 */
    public List<UserFact> activeByUserAndCategory(long userId, String category, int limit) {
        String safeCategory = normalizeCategory(category);
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

    /**
     * 软失效一条旧事实；expectedGeneration 为 {@link Long#MIN_VALUE} 时不做代际围栏。
     */
    public boolean markSuperseded(long userId, long factId, long supersededById, long expectedGeneration) {
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

    public void deleteByUser(long userId) {
        jdbc.update("DELETE FROM user_facts WHERE user_id=?", userId);
    }

    /** 用户只能删除自己的有效事实。 */
    public boolean deleteByIdForUser(long id, long userId) {
        return jdbc.update("DELETE FROM user_facts WHERE id=? AND user_id=? AND status='active'", id, userId) > 0;
    }

    /** 用户显式删除 Episode 时级联删除其来源事实；系统保留清理不调用此方法。 */
    public int deleteBySourceEpisodeId(long userId, long sourceEpisodeId) {
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

    private UserFact mapFact(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new UserFact(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getDouble(4),
                rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant());
    }

    public static String normalizeCategory(String category) {
        if (category == null) return "background";
        String value = category.trim().toLowerCase(Locale.ROOT);
        return CATEGORIES.contains(value) ? value : "background";
    }

    /** 幂等键：canonical 文本（小写、去标点空白）的 sha-256，避免同义重复事实重复入库。 */
    public static String hashOf(String factText) {
        String canonical = factText == null ? "" : factText.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String normalizeKeyId(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
