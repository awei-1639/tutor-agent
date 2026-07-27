package com.tutor.memory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * L2 情景记忆 (Phase 3 V4 3.1): 会话级摘要向量化, 跨会话检索"我们之前聊过什么"。
 * schema: episodes(id, user_id, conversation_id, summary, topics[], open_items[], embedding)
 * 实施: ChatService 回答完成后异步触发 summarizer; 检索时相似度匹配返回 topK
 */
@Component
public class EpisodeStore {
    private final JdbcTemplate jdbc;

    public EpisodeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Episode(long id, long userId, Long conversationId,
                          String summary, List<String> topics, List<String> openItems) {}

    /** 插入 episode (embedding 单独 update, 因 pgvector 不支持 INSERT...VALUES (?,?::vector)?) */
    public long insert(long userId, Long conversationId, String summary,
                       List<String> topics, List<String> openItems, float[] embedding) {
        // topics/open_items 是 text[] 列, 用 PG 字符串数组字面量 {a,b}, 需 PG 数组 cast
        long id = jdbc.queryForObject(
                "INSERT INTO episodes (user_id, conversation_id, summary, topics, open_items) " +
                        "VALUES (?,?,?,?::text[],?::text[]) RETURNING id",
                Long.class, userId, conversationId, summary,
                toPgTextArrayLiteral(topics), toPgTextArrayLiteral(openItems));
        if (embedding != null && embedding.length > 0) {
            jdbc.update("UPDATE episodes SET embedding = ?::vector WHERE id = ?",
                    com.tutor.retrieval.VectorStore.toVectorLiteral(embedding), id);
        }
        return id;
    }

    /** 向量相似检索 topK (同用户范围) */
    public List<Episode> searchByEmbedding(long userId, float[] queryVec, int topK) {
        String vec = com.tutor.retrieval.VectorStore.toVectorLiteral(queryVec);
        return jdbc.query(
                "SELECT id, user_id, conversation_id, summary, topics, open_items " +
                        "FROM episodes WHERE user_id = ? AND embedding IS NOT NULL " +
                        "ORDER BY embedding <=> ?::vector LIMIT ?",
                (rs, i) -> new Episode(
                        rs.getLong(1), rs.getLong(2),
                        rs.getObject(3) == null ? null : rs.getLong(3),
                        rs.getString(4),
                        parsePgTextArray(rs.getString(5)),
                        parsePgTextArray(rs.getString(6))),
                userId, vec, topK);
    }

    /** 按用户取最近 N 条 (无向量检索时降级) */
    public List<Episode> recentByUser(long userId, int limit) {
        return jdbc.query(
                "SELECT id, user_id, conversation_id, summary, topics, open_items " +
                        "FROM episodes WHERE user_id = ? ORDER BY created_at DESC LIMIT ?",
                (rs, i) -> new Episode(
                        rs.getLong(1), rs.getLong(2),
                        rs.getObject(3) == null ? null : rs.getLong(3),
                        rs.getString(4),
                        parsePgTextArray(rs.getString(5)),
                        parsePgTextArray(rs.getString(6))),
                userId, limit);
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