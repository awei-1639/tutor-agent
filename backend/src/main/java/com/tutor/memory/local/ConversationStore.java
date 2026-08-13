package com.tutor.memory.local;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** L1 工作记忆最小版: 会话/消息持久化 + 最近N轮读取。滚动摘要折叠在超12轮后接入 (实现设计 2.1)。 */
@Component
public class ConversationStore {
    private final JdbcTemplate jdbc;

    public ConversationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static class Msg {
        public final long id;
        public final String role;
        public final String content;
        public String citations; // mutable: 历史消息可补
        public String citationStatus;
        public String citationIssues;
        public String traceId;
        public String feedback;
        public Msg(String role, String content) {
            this(0, role, content);
        }
        public Msg(long id, String role, String content) {
            this.id = id; this.role = role; this.content = content;
        }
    }

    public long ensureConversation(Long conversationId, long userId) {
        if (conversationId != null) {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM conversations WHERE id=? AND user_id=?",
                    Integer.class, conversationId, userId);
            if (n != null && n > 0) {
                jdbc.update("UPDATE conversations SET last_active_at=now() WHERE id=? AND user_id=?", conversationId, userId);
                return conversationId;
            }
            throw new IllegalStateException("会话不存在或无访问权限");
        }
        jdbc.update("INSERT INTO users (id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        return jdbc.queryForObject(
                "INSERT INTO conversations (user_id, last_active_at) VALUES (?, now()) RETURNING id",
                Long.class, userId);
    }


    public void updateCitationVerification(long messageId, String status, String issuesJson) {
        jdbc.update("UPDATE messages SET citation_status=?, citation_issues=?::jsonb, citation_checked_at=now() WHERE id=?",
                status, issuesJson == null ? "[]" : issuesJson, messageId);
    }

    public long appendMessage(long conversationId, String role, String content,
                              String intent, String citationsJson, String traceId, int tokenCount) {
        return appendMessage(conversationId, role, content, intent, citationsJson, traceId, tokenCount, null, null);
    }

    public long appendMessage(long conversationId, String role, String content,
                              String intent, String citationsJson, int tokenCount) {
        return appendMessage(conversationId, role, content, intent, citationsJson, null, tokenCount, null, null);
    }

    /** Persists a completed turn together with its initial citation-guard status. */
    public long appendMessage(long conversationId, String role, String content,
                              String intent, String citationsJson, String traceId, int tokenCount,
                              String citationStatus, String citationIssuesJson) {
        return jdbc.queryForObject("""
                INSERT INTO messages (conversation_id, role, content, intent, citations, trace_id, token_count,
                                      citation_status, citation_issues)
                VALUES (?,?,?,?,?::jsonb,?,?,?,?::jsonb) RETURNING id
                """, Long.class, conversationId, role, content, intent, citationsJson, traceId, tokenCount,
                citationStatus, citationIssuesJson == null ? "[]" : citationIssuesJson);
    }

    /** 最近N轮原文, 时间正序 (含 citations: 已用于回填溯源面板) */
    public List<Msg> recentMessages(long conversationId, int limit) {
        List<Msg> desc = jdbc.query(
                "SELECT id, role, content, citations, trace_id, citation_status, citation_issues FROM messages WHERE conversation_id=? ORDER BY id DESC LIMIT ?",
                (rs, i) -> {
                    String citations = rs.getString(4);
                    Msg m = new Msg(rs.getLong(1), rs.getString(2), rs.getString(3));
                    m.citations = citations; // 字符串 JSON, 前端按 string 处理
                    m.traceId = rs.getString(5);
                    m.citationStatus = rs.getString(6);
                    m.citationIssues = rs.getString(7);
                    return m;
                }, conversationId, limit);
        return desc.reversed();
    }

    /** 对外读取必须验证会话归属，防止通过递增 ID 枚举其他用户的消息。 */
    public List<Msg> recentMessagesForUser(long conversationId, long userId, int limit) {
        List<Msg> desc = jdbc.query("""
                SELECT m.id, m.role, m.content, m.citations, m.trace_id, mf.rating, m.citation_status, m.citation_issues
                FROM messages m JOIN conversations c ON c.id=m.conversation_id
                LEFT JOIN message_feedback mf ON mf.message_id=m.id AND mf.user_id=?
                WHERE m.conversation_id=? AND c.user_id=?
                ORDER BY m.id DESC LIMIT ?
                """, (rs, i) -> {
                    Msg m = new Msg(rs.getLong(1), rs.getString(2), rs.getString(3));
                    m.citations = rs.getString(4);
                    m.traceId = rs.getString(5);
                    m.feedback = rs.getString(6);
                    m.citationStatus = rs.getString(7);
                    m.citationIssues = rs.getString(8);
                    return m;
                }, userId, conversationId, userId, limit);
        return desc.reversed();
    }

    public boolean belongsToUser(long conversationId, long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM conversations WHERE id=? AND user_id=?",
                Integer.class, conversationId, userId);
        return count != null && count > 0;
    }

    /** 用户会话列表 (按最后活跃时间倒序) */
    public List<java.util.Map<String, Object>> listConversations(long userId) {
        return jdbc.query(
                "SELECT c.id, c.last_active_at, " +
                        "  (SELECT content FROM messages WHERE conversation_id=c.id AND role='user' ORDER BY id LIMIT 1) AS title, " +
                        "  (SELECT count(*) FROM messages WHERE conversation_id=c.id) AS msg_count " +
                        "FROM conversations c WHERE c.user_id=? ORDER BY c.last_active_at DESC NULLS LAST LIMIT 50",
                (rs, i) -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", rs.getLong(1));
                    m.put("last_active_at", rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant().toString());
                    m.put("title", rs.getString(3));
                    m.put("msg_count", rs.getLong(4));
                    return m;
                }, userId);
    }

    public record SummaryState(String summary, long uptoMsgId) {}

    public SummaryState summaryState(long conversationId) {
        return jdbc.query("SELECT summary, COALESCE(summary_upto_msg_id,0) FROM conversations WHERE id=?",
                        (rs, i) -> new SummaryState(rs.getString(1), rs.getLong(2)), conversationId)
                .stream().findFirst().orElse(new SummaryState(null, 0));
    }

    public long episodeUptoMsgId(long conversationId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(episode_upto_msg_id,0) FROM conversations WHERE id=?", Long.class, conversationId);
        return value == null ? 0 : value;
    }

    public List<Msg> messagesAfter(long conversationId, long messageId, int limit) {
        return jdbc.query("""
                SELECT id, role, content FROM messages WHERE conversation_id=? AND id>? ORDER BY id LIMIT ?
                """, (rs, i) -> new Msg(rs.getLong(1), rs.getString(2), rs.getString(3)), conversationId, messageId, limit);
    }

    public void advanceEpisodeWatermark(long conversationId, long messageId) {
        jdbc.update("UPDATE conversations SET episode_upto_msg_id=GREATEST(COALESCE(episode_upto_msg_id,0), ?) WHERE id=?", messageId, conversationId);
    }

    /** 待折叠消息: 已被摘要覆盖之后、且不在最近keepRecent条窗口内的原文 (时间正序) */
    public List<Msg> messagesToFold(long conversationId, long uptoMsgId, int keepRecent) {
        return jdbc.query("""
                SELECT role, content FROM messages
                WHERE conversation_id = ? AND id > ?
                  AND id <= (SELECT COALESCE(MIN(id),0) - 1 FROM (
                        SELECT id FROM messages WHERE conversation_id = ? ORDER BY id DESC LIMIT ?) recent)
                ORDER BY id
                """, (rs, i) -> new Msg(rs.getString(1), rs.getString(2)),
                conversationId, uptoMsgId, conversationId, keepRecent);
    }

    public long maxFoldableMsgId(long conversationId, int keepRecent) {
        Long v = jdbc.queryForObject("""
                SELECT COALESCE(MIN(id),0) - 1 FROM (
                    SELECT id FROM messages WHERE conversation_id = ? ORDER BY id DESC LIMIT ?) recent
                """, Long.class, conversationId, keepRecent);
        return v == null ? 0 : v;
    }

    public void saveSummary(long conversationId, String summary, long uptoMsgId) {
        jdbc.update("UPDATE conversations SET summary=?, summary_upto_msg_id=? WHERE id=?",
                summary, uptoMsgId, conversationId);
    }
}
