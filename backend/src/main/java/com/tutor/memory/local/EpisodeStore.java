package com.tutor.memory.local;

import com.tutor.resume.PiiMasker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.time.Instant;

/**
 * L2 情景记忆 (Phase 3 V4 3.1): 会话级摘要向量化, 跨会话检索"我们之前聊过什么"。
 * schema: episodes(id, user_id, conversation_id, summary, topics[], open_items[], embedding)
 * 实施: ChatService 回答完成后异步触发 summarizer; 检索时相似度匹配返回 topK
 */
@Component
public class EpisodeStore {
    private final JdbcTemplate jdbc;
    private final String encKey;
    private final String encKeyId;
    private final String previousEncKey;
    private final String previousEncKeyId;

    public EpisodeStore(JdbcTemplate jdbc) {
        this(jdbc, "", "v1", "", "");
    }

    public EpisodeStore(JdbcTemplate jdbc, String encKey) {
        this(jdbc, encKey, "v1", "", "");
    }

    @Autowired
    public EpisodeStore(JdbcTemplate jdbc,
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

    public record Episode(long id, long userId, Long conversationId,
                          String summary, List<String> topics, List<String> openItems,
                          double relevance, String remoteMemoryId, Instant createdAt) {
        public Episode(long id, long userId, Long conversationId,
                       String summary, List<String> topics, List<String> openItems) {
            this(id, userId, conversationId, summary, topics, openItems, 0D, null, null);
        }

        public Episode(long id, long userId, Long conversationId,
                       String summary, List<String> topics, List<String> openItems,
                       double relevance) {
            this(id, userId, conversationId, summary, topics, openItems, relevance, null, null);
        }

        public Episode(long id, long userId, Long conversationId,
                       String summary, List<String> topics, List<String> openItems,
                       double relevance, String remoteMemoryId) {
            this(id, userId, conversationId, summary, topics, openItems, relevance, remoteMemoryId, null);
        }
    }

    public record ManagedEpisode(long id, String summary, List<String> topics, List<String> openItems,
                                 Instant createdAt, Instant expiresAt) {}

    /** 插入 episode (embedding 单独 update, 因 pgvector 不支持 INSERT...VALUES (?,?::vector)?) */
    public long insert(long userId, Long conversationId, String summary,
                       List<String> topics, List<String> openItems, float[] embedding) {
        if (!encKey.isBlank()) {
            String maskedSummary = PiiMasker.mask(summary).masked();
            long id = jdbc.queryForObject(
                    "INSERT INTO episodes (user_id, conversation_id, summary, summary_encrypted, summary_encryption_key_id, topics, open_items) " +
                            "VALUES (?,?,?,pgp_sym_encrypt(?,?),?,?::text[],?::text[]) RETURNING id",
                    Long.class, userId, conversationId, maskedSummary, summary, encKey, encKeyId,
                    toPgTextArrayLiteral(maskedList(topics)), toPgTextArrayLiteral(maskedList(openItems)));
            if (embedding != null && embedding.length > 0) {
                jdbc.update("UPDATE episodes SET embedding = ?::vector WHERE id = ?",
                        com.tutor.retrieval.vector.VectorStore.toVectorLiteral(embedding), id);
            }
            return id;
        }
        // topics/open_items 是 text[] 列, 用 PG 字符串数组字面量 {a,b}, 需 PG 数组 cast
        long id = jdbc.queryForObject(
                "INSERT INTO episodes (user_id, conversation_id, summary, topics, open_items) " +
                        "VALUES (?,?,?,?::text[],?::text[]) RETURNING id",
                Long.class, userId, conversationId, summary,
                toPgTextArrayLiteral(topics), toPgTextArrayLiteral(openItems));
        if (embedding != null && embedding.length > 0) {
            jdbc.update("UPDATE episodes SET embedding = ?::vector WHERE id = ?",
                    com.tutor.retrieval.vector.VectorStore.toVectorLiteral(embedding), id);
        }
        return id;
    }

    /** 将向量与 Episode 一并写入，并通过唯一来源窗口保证幂等性。 */
    public int insertIfAbsent(long userId, long conversationId, String summary,
                              List<String> topics, List<String> openItems, float[] embedding,
                              long sourceFromMessageId, long sourceToMessageId, long memoryGeneration) {
        return insertIfAbsentReturningId(userId, conversationId, summary, topics, openItems, embedding,
                sourceFromMessageId, sourceToMessageId, memoryGeneration) > 0 ? 1 : 0;
    }

    /** 插入并返回本地记忆 ID；重复来源窗口返回 0。 */
    public long insertIfAbsentReturningId(long userId, long conversationId, String summary,
                                          List<String> topics, List<String> openItems, float[] embedding,
                                          long sourceFromMessageId, long sourceToMessageId, long memoryGeneration) {
        String vector = embedding == null || embedding.length == 0 ? null
                : com.tutor.retrieval.vector.VectorStore.toVectorLiteral(embedding);
        String maskedSummary = encKey.isBlank() ? summary : PiiMasker.mask(summary).masked();
        String topicsLiteral = toPgTextArrayLiteral(encKey.isBlank() ? topics : maskedList(topics));
        String openItemsLiteral = toPgTextArrayLiteral(encKey.isBlank() ? openItems : maskedList(openItems));
        String sql = encKey.isBlank() ? """
                INSERT INTO episodes (user_id, conversation_id, summary, topics, open_items, embedding,
                                      source_from_msg_id, source_to_msg_id, memory_generation)
                VALUES (?,?,?,?::text[],?::text[],?::vector,?,?,?)
                ON CONFLICT (user_id, conversation_id, source_from_msg_id, source_to_msg_id)
                WHERE source_from_msg_id IS NOT NULL AND source_to_msg_id IS NOT NULL DO NOTHING
                RETURNING id
                """ : """
                INSERT INTO episodes (user_id, conversation_id, summary, summary_encrypted, summary_encryption_key_id,
                                      topics, open_items, embedding, source_from_msg_id, source_to_msg_id, memory_generation)
                VALUES (?,?,?,pgp_sym_encrypt(?,?),?,?::text[],?::text[],?::vector,?,?,?)
                ON CONFLICT (user_id, conversation_id, source_from_msg_id, source_to_msg_id)
                WHERE source_from_msg_id IS NOT NULL AND source_to_msg_id IS NOT NULL DO NOTHING
                RETURNING id
                """;
        Object[] args = encKey.isBlank()
                ? new Object[]{userId, conversationId, summary, topicsLiteral, openItemsLiteral, vector,
                sourceFromMessageId, sourceToMessageId, memoryGeneration}
                : new Object[]{userId, conversationId, maskedSummary, summary, encKey, encKeyId, topicsLiteral,
                openItemsLiteral, vector, sourceFromMessageId, sourceToMessageId, memoryGeneration};
        List<Long> ids = jdbc.query(sql, (rs, rowNum) -> rs.getLong(1), args);
        return ids.isEmpty() ? 0L : ids.getFirst();
    }

    /** 向量相似检索 topK (同用户范围) */
    public List<Episode> searchByEmbedding(long userId, float[] queryVec, int topK) {
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

    /** 按用户取最近 N 条 (无向量检索时降级) */
    public List<Episode> recentByUser(long userId, int limit) {
        String sql = "SELECT id, user_id, conversation_id, " + summaryColumn() + ", topics, open_items, remote_memory_id, created_at " +
                "FROM episodes WHERE user_id = ? AND status='active' " +
                "AND (expires_at IS NULL OR expires_at > now()) ORDER BY created_at DESC LIMIT ?";
        return encKey.isBlank()
                ? jdbc.query(sql, this::mapRecentEpisode, userId, limit)
                : jdbc.query(sql, this::mapRecentEpisode, queryArgs(userId, limit));
    }

    public void deleteByUser(long userId) {
        jdbc.update("DELETE FROM episodes WHERE user_id=?", userId);
    }

    /** 用户最近有效记忆中的未决事项（按记忆新旧去重取前 N），用于新会话开场主动提醒。 */
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

    public List<ManagedEpisode> activeByUser(long userId, int limit) {
        String sql = """
                SELECT id, %s, topics, open_items, created_at, expires_at
                FROM episodes
                WHERE user_id=? AND status='active' AND (expires_at IS NULL OR expires_at > now())
                ORDER BY created_at DESC LIMIT ?
                """.formatted(summaryColumn());
        return encKey.isBlank() ? jdbc.query(sql, (rs, i) -> new ManagedEpisode(rs.getLong(1), rs.getString(2),
                parsePgTextArray(rs.getString(3)), parsePgTextArray(rs.getString(4)),
                rs.getTimestamp(5).toInstant(), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant()),
                userId, limit) : jdbc.query(sql, (rs, i) -> new ManagedEpisode(rs.getLong(1), rs.getString(2),
                parsePgTextArray(rs.getString(3)), parsePgTextArray(rs.getString(4)),
                rs.getTimestamp(5).toInstant(), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant()),
                queryArgs(userId, limit));
    }

    /** 用户只能删除自己的有效本地记忆。 */
    public boolean deleteByIdForUser(long id, long userId) {
        return jdbc.update("""
                WITH deleted AS (
                    DELETE FROM episodes WHERE id=? AND user_id=? RETURNING id, user_id
                )
                INSERT INTO episode_memory_tombstones (user_id, memory_id)
                SELECT user_id, id FROM deleted
                ON CONFLICT (user_id, memory_id) DO NOTHING
                """, id, userId) > 0;
    }

    /** 查询远端副本标识；仅返回当前用户的有效本地记忆。 */
    public java.util.Optional<String> remoteMemoryIdById(long id, long userId) {
        return jdbc.query("""
                SELECT remote_memory_id FROM episodes
                WHERE id=? AND user_id=? AND status='active'
                  AND (expires_at IS NULL OR expires_at > now())
                """, (rs, i) -> rs.getString(1), id, userId)
                .stream().filter(value -> value != null && !value.isBlank()).findFirst();
    }

    /** 将经过归属校验的 Mem0 远端 UUID 回写到本地权威记录。 */
    public void recordRemoteMemoryId(long id, long userId, String remoteMemoryId) {
        if (!com.tutor.memory.RemoteMemoryId.isValid(remoteMemoryId)) return;
        jdbc.update("""
                UPDATE episodes SET remote_memory_id=?
                WHERE id=? AND user_id=? AND status='active'
                """, remoteMemoryId, id, userId);
    }

    /** 远程副本回填前确认本地权威记录仍然有效。 */
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

    private static String normalizeKeyId(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private Episode mapEpisode(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Episode(rs.getLong(1), rs.getLong(2),
                rs.getObject(3) == null ? null : rs.getLong(3), rs.getString(4),
                parsePgTextArray(rs.getString(5)), parsePgTextArray(rs.getString(6)), rs.getDouble(7),
                rs.getString(8), toInstant(rs.getTimestamp(9)));
    }

    private Episode mapRecentEpisode(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Episode(rs.getLong(1), rs.getLong(2),
                rs.getObject(3) == null ? null : rs.getLong(3), rs.getString(4),
                parsePgTextArray(rs.getString(5)), parsePgTextArray(rs.getString(6)), 0D,
                rs.getString(7), toInstant(rs.getTimestamp(8)));
    }

    private static Instant toInstant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String toPgTextArrayLiteral(List<String> items) {
        if (items == null || items.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(items.get(i).replace("\"", "\\\"").replace("\\", "\\\\")).append('"');
        }
        return sb.append('}').toString();
    }

    private static List<String> maskedList(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().map(value -> PiiMasker.mask(value == null ? "" : value).masked()).toList();
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
