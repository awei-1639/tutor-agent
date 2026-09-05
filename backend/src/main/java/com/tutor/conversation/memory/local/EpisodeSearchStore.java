package com.tutor.conversation.memory.local;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Owns read-side episode queries, decryption-aware projections, and row mapping. */
final class EpisodeSearchStore {
    private final JdbcTemplate jdbc;
    private final String encKey;
    private final String encKeyId;
    private final String previousEncKey;
    private final String previousEncKeyId;

    EpisodeSearchStore(JdbcTemplate jdbc, String encKey, String encKeyId,
                       String previousEncKey, String previousEncKeyId) {
        this.jdbc = jdbc;
        this.encKey = encKey == null ? "" : encKey;
        this.encKeyId = encKeyId == null || encKeyId.isBlank() ? "v1" : encKeyId.trim();
        this.previousEncKey = previousEncKey == null ? "" : previousEncKey;
        this.previousEncKeyId = previousEncKeyId == null ? "" : previousEncKeyId.trim();
    }

    public List<EpisodeStore.Episode> searchByEmbedding(long userId, float[] queryVec, int topK) {
        String vec = com.tutor.retrieval.vector.VectorStore.toVectorLiteral(queryVec);
        String summary = summaryColumn();
        String sql = "SELECT id, user_id, conversation_id, " + summary + ", topics, open_items, " +
                "1 - (embedding <=> ?::vector) AS relevance, remote_memory_id, created_at " +
                "FROM episodes WHERE user_id = ? AND status='active' AND embedding IS NOT NULL " +
                "AND (expires_at IS NULL OR expires_at > now()) " +
                "AND 1 - (embedding <=> ?::vector) >= ? " +
                "ORDER BY embedding <=> ?::vector LIMIT ?";
        return encKey.isBlank()
                ? jdbc.query(sql, this::mapEpisode, vec, userId, vec, 0.50, vec, topK)
                : jdbc.query(sql, this::mapEpisode, queryArgs(vec, userId, vec, 0.50, vec, topK));
    }

    public List<EpisodeStore.Episode> recentByUser(long userId, int limit) {
        String sql = "SELECT id, user_id, conversation_id, " + summaryColumn() + ", topics, open_items, remote_memory_id, created_at " +
                "FROM episodes WHERE user_id = ? AND status='active' " +
                "AND (expires_at IS NULL OR expires_at > now()) ORDER BY created_at DESC LIMIT ?";
        return encKey.isBlank()
                ? jdbc.query(sql, this::mapRecentEpisode, userId, limit)
                : jdbc.query(sql, this::mapRecentEpisode, queryArgs(userId, limit));
    }

    public List<String> openItemsByUser(long userId, int limit) {
        List<String> rows = jdbc.query("""
                SELECT open_items FROM episodes
                WHERE user_id = ? AND status='active' AND (expires_at IS NULL OR expires_at > now())
                ORDER BY created_at DESC LIMIT 20
                """, (rs, i) -> rs.getString(1), userId);
        List<String> result = new ArrayList<>();
        for (String row : rows) {
            for (String item : parsePgTextArray(row)) {
                String value = item.trim();
                if (!value.isEmpty() && !result.contains(value)) result.add(value);
                if (result.size() >= limit) return result;
            }
        }
        return result;
    }

    public List<EpisodeStore.ManagedEpisode> activeByUser(long userId, int limit) {
        String sql = """
                SELECT id, %s, topics, open_items, created_at, expires_at
                FROM episodes
                WHERE user_id=? AND status='active' AND (expires_at IS NULL OR expires_at > now())
                ORDER BY created_at DESC LIMIT ?
                """.formatted(summaryColumn());
        return encKey.isBlank() ? jdbc.query(sql, (rs, i) -> new EpisodeStore.ManagedEpisode(rs.getLong(1), rs.getString(2),
                parsePgTextArray(rs.getString(3)), parsePgTextArray(rs.getString(4)),
                rs.getTimestamp(5).toInstant(), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant()),
                userId, limit) : jdbc.query(sql, (rs, i) -> new EpisodeStore.ManagedEpisode(rs.getLong(1), rs.getString(2),
                parsePgTextArray(rs.getString(3)), parsePgTextArray(rs.getString(4)),
                rs.getTimestamp(5).toInstant(), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant()),
                queryArgs(userId, limit));
    }

    public java.util.Optional<String> remoteMemoryIdById(long id, long userId) {
        return jdbc.query("""
                SELECT remote_memory_id FROM episodes
                WHERE id=? AND user_id=? AND status='active'
                  AND (expires_at IS NULL OR expires_at > now())
                """, (rs, i) -> rs.getString(1), id, userId)
                .stream().filter(value -> value != null && !value.isBlank()).findFirst();
    }

    public boolean isActiveById(long id, long userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM episodes
                WHERE id=? AND user_id=? AND status='active'
                  AND (expires_at IS NULL OR expires_at > now())
                """, Integer.class, id, userId);
        return count != null && count > 0;
    }

    private String summaryColumn() {
        if (encKey.isBlank()) return "summary";
        if (!hasPreviousKey()) {
            return """
                    CASE
                        WHEN summary_encrypted IS NOT NULL AND summary_encryption_key_id=?
                            THEN pgp_sym_decrypt(summary_encrypted, ?)
                        ELSE summary
                    END
                    """;
        }
        return """
                CASE
                    WHEN summary_encrypted IS NOT NULL AND summary_encryption_key_id=?
                        THEN pgp_sym_decrypt(summary_encrypted, ?)
                    WHEN summary_encrypted IS NOT NULL AND summary_encryption_key_id=?
                        THEN pgp_sym_decrypt(summary_encrypted, ?)
                    ELSE summary
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

    private EpisodeStore.Episode mapEpisode(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new EpisodeStore.Episode(rs.getLong(1), rs.getLong(2),
                rs.getObject(3) == null ? null : rs.getLong(3), rs.getString(4),
                parsePgTextArray(rs.getString(5)), parsePgTextArray(rs.getString(6)), rs.getDouble(7),
                rs.getString(8), toInstant(rs.getTimestamp(9)));
    }

    private EpisodeStore.Episode mapRecentEpisode(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new EpisodeStore.Episode(rs.getLong(1), rs.getLong(2),
                rs.getObject(3) == null ? null : rs.getLong(3), rs.getString(4),
                parsePgTextArray(rs.getString(5)), parsePgTextArray(rs.getString(6)), 0D,
                rs.getString(7), toInstant(rs.getTimestamp(8)));
    }

    private static Instant toInstant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static List<String> parsePgTextArray(String pg) {
        if (pg == null || pg.length() < 2) return List.of();
        String inner = pg.substring(1, pg.length() - 1);
        if (inner.isEmpty()) return List.of();
        // 简化解析: 支持基础逗号分隔 + 双引号转义
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '"' && (i == 0 || inner.charAt(i - 1) != '\\')) inQuote = !inQuote;
            else if (c == ',' && !inQuote) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
