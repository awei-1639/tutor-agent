package com.tutor.memory.local;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.tutor.resume.PiiMasker;

import java.util.List;
import java.time.Instant;

/** L1 工作记忆最小版: 会话/消息持久化 + 最近N轮读取。滚动摘要折叠在超12轮后接入 (实现设计 2.1)。 */
@Component
public class ConversationStore {
    private final JdbcTemplate jdbc;
    private final String encKey;

    public ConversationStore(JdbcTemplate jdbc) {
        this(jdbc, "");
    }

    @Autowired
    public ConversationStore(JdbcTemplate jdbc, @Value("${security.resume-enc-key:}") String encKey) {
        this.jdbc = jdbc;
        this.encKey = encKey == null ? "" : encKey;
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

    /** 持久化已完成轮次及其初始引用防护状态。 */
    public long appendMessage(long conversationId, String role, String content,
                              String intent, String citationsJson, String traceId, int tokenCount,
                              String citationStatus, String citationIssuesJson) {
        return appendMessage(conversationId, role, content, intent, citationsJson, traceId, tokenCount,
                citationStatus, citationIssuesJson, null);
    }

    /**
     * Persist a message belonging to a durable chat turn. The unique database
     * constraint on chat_turn_id makes recovery safe: a retried worker cannot
     * create a second copy of the same user or assistant message.
     */
    public long appendMessage(long conversationId, String role, String content,
                              String intent, String citationsJson, String traceId, int tokenCount,
                              String citationStatus, String citationIssuesJson, String chatTurnId) {
        citationStatus = citationStatus == null || citationStatus.isBlank() ? "not_applicable" : citationStatus;
        if (!encKey.isBlank()) {
            String masked = PiiMasker.mask(content).masked();
            return jdbc.queryForObject("""
                    INSERT INTO messages (conversation_id, role, content, content_encrypted, intent, citations, trace_id, token_count,
                                          citation_status, citation_issues, chat_turn_id)
                    VALUES (?,?,?,pgp_sym_encrypt(?,?),?,?,?,?,?,?::jsonb,?::uuid) RETURNING id
                    """, Long.class, conversationId, role, masked, content, encKey, intent, citationsJson,
                    traceId, tokenCount, citationStatus, citationIssuesJson == null ? "[]" : citationIssuesJson, chatTurnId);
        }
        return jdbc.queryForObject("""
                INSERT INTO messages (conversation_id, role, content, intent, citations, trace_id, token_count,
                                      citation_status, citation_issues, chat_turn_id)
                VALUES (?,?,?,?,?::jsonb,?,?,?,?::jsonb,?::uuid) RETURNING id
                """, Long.class, conversationId, role, content, intent, citationsJson, traceId, tokenCount,
                citationStatus, citationIssuesJson == null ? "[]" : citationIssuesJson, chatTurnId);
    }

    /** 最近N轮原文, 时间正序 (含 citations: 已用于回填溯源面板) */
    public List<Msg> recentMessages(long conversationId, int limit) {
        String sql = encKey.isBlank()
                ? "SELECT id, role, content, citations, trace_id, citation_status, citation_issues FROM messages WHERE conversation_id=? ORDER BY id DESC LIMIT ?"
                : "SELECT id, role, COALESCE(pgp_sym_decrypt(content_encrypted, ?), content), citations, trace_id, citation_status, citation_issues FROM messages WHERE conversation_id=? ORDER BY id DESC LIMIT ?";
        List<Msg> desc = encKey.isBlank() ? jdbc.query(sql,
                (rs, i) -> {
                    String citations = rs.getString(4);
                    Msg m = new Msg(rs.getLong(1), rs.getString(2), rs.getString(3));
                    m.citations = citations; // 字符串 JSON, 前端按 string 处理
                    m.traceId = rs.getString(5);
                    m.citationStatus = rs.getString(6);
                    m.citationIssues = rs.getString(7);
                    return m;
                }, conversationId, limit) : jdbc.query(sql,
                (rs, i) -> {
                    String citations = rs.getString(4);
                    Msg m = new Msg(rs.getLong(1), rs.getString(2), rs.getString(3));
                    m.citations = citations;
                    m.traceId = rs.getString(5);
                    m.citationStatus = rs.getString(6);
                    m.citationIssues = rs.getString(7);
                    return m;
                }, encKey, conversationId, limit);
        return desc.reversed();
    }

    /** 对外读取必须验证会话归属，防止通过递增 ID 枚举其他用户的消息。 */
    public List<Msg> recentMessagesForUser(long conversationId, long userId, int limit) {
        String sql = """
                SELECT m.id, m.role, %s, m.citations, m.trace_id, mf.rating, m.citation_status, m.citation_issues
                FROM messages m JOIN conversations c ON c.id=m.conversation_id
                LEFT JOIN message_feedback mf ON mf.message_id=m.id AND mf.user_id=?
                WHERE m.conversation_id=? AND c.user_id=?
                ORDER BY m.id DESC LIMIT ?
                """.formatted(encKey.isBlank() ? "m.content" : "COALESCE(pgp_sym_decrypt(m.content_encrypted, ?), m.content)");
        List<Msg> desc = encKey.isBlank() ? jdbc.query(sql, (rs, i) -> {
                    Msg m = new Msg(rs.getLong(1), rs.getString(2), rs.getString(3));
                    m.citations = rs.getString(4);
                    m.traceId = rs.getString(5);
                    m.feedback = rs.getString(6);
                    m.citationStatus = rs.getString(7);
                    m.citationIssues = rs.getString(8);
                    return m;
                }, userId, conversationId, userId, limit) : jdbc.query(sql, (rs, i) -> {
                    Msg m = new Msg(rs.getLong(1), rs.getString(2), rs.getString(3));
                    m.citations = rs.getString(4);
                    m.traceId = rs.getString(5);
                    m.feedback = rs.getString(6);
                    m.citationStatus = rs.getString(7);
                    m.citationIssues = rs.getString(8);
                    return m;
                }, encKey, userId, conversationId, userId, limit);
        return desc.reversed();
    }

    public boolean belongsToUser(long conversationId, long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM conversations WHERE id=? AND user_id=?",
                Integer.class, conversationId, userId);
        return count != null && count > 0;
    }

    /** 判断后台记忆任务是否仍属于其启动时的记忆代际。 */
    public boolean memoryGenerationCurrent(long conversationId, long userId, long generation) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM conversations c
                JOIN users u ON u.id=c.user_id
                WHERE c.id=? AND c.user_id=? AND u.memory_generation=?
                """, Integer.class, conversationId, userId, generation);
        return count != null && count > 0;
    }

    /** 删除所属用户的一条会话及其原始消息。 */
    public boolean deleteConversationForUser(long conversationId, long userId) {
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM conversations WHERE id=? AND user_id=?",
                Integer.class, conversationId, userId);
        if (exists == null || exists == 0) return false;
        jdbc.update("DELETE FROM messages WHERE conversation_id=?", conversationId);
        jdbc.update("DELETE FROM conversations WHERE id=? AND user_id=?", conversationId, userId);
        return true;
    }

    /** 删除用户的全部会话消息和摘要。 */
    public void deleteAllForUser(long userId) {
        jdbc.update("DELETE FROM messages WHERE conversation_id IN (SELECT id FROM conversations WHERE user_id=?)", userId);
        jdbc.update("DELETE FROM conversations WHERE user_id=?", userId);
    }

    /** 用户会话列表 (按最后活跃时间倒序) */
    public List<java.util.Map<String, Object>> listConversations(long userId) {
        String title = encKey.isBlank() ? "content" : "COALESCE(pgp_sym_decrypt(content_encrypted, ?), content)";
        String sql = "SELECT c.id, c.last_active_at, " +
                        "  (SELECT " + title + " FROM messages WHERE conversation_id=c.id AND role='user' ORDER BY id LIMIT 1) AS title, " +
                        "  (SELECT count(*) FROM messages WHERE conversation_id=c.id) AS msg_count " +
                        "FROM conversations c WHERE c.user_id=? ORDER BY c.last_active_at DESC NULLS LAST LIMIT 50";
        return encKey.isBlank() ? jdbc.query(sql,
                (rs, i) -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", rs.getLong(1));
                    m.put("last_active_at", rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant().toString());
                    m.put("title", rs.getString(3));
                    m.put("msg_count", rs.getLong(4));
                    return m;
                }, userId) : jdbc.query(sql,
                (rs, i) -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", rs.getLong(1));
                    m.put("last_active_at", rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant().toString());
                    m.put("title", rs.getString(3));
                    m.put("msg_count", rs.getLong(4));
                    return m;
                }, encKey, userId);
    }

    public record SummaryState(String summary, long uptoMsgId) {}

    public record ClarificationState(boolean pending, String intent, Instant expiresAt) {
        static ClarificationState none() {
            return new ClarificationState(false, null, null);
        }
    }

    public ClarificationState clarificationState(long conversationId) {
        List<ClarificationState> rows = jdbc.query("""
                SELECT clarification_pending, clarification_intent, clarification_expires_at
                FROM conversations WHERE id=?
                """, (rs, i) -> new ClarificationState(rs.getBoolean(1), rs.getString(2),
                rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()), conversationId);
        ClarificationState state = rows.stream().findFirst().orElse(ClarificationState.none());
        if (state.pending() && state.expiresAt() != null && state.expiresAt().isBefore(Instant.now())) {
            clearClarification(conversationId);
            return ClarificationState.none();
        }
        return state;
    }

    public void setClarificationPending(long conversationId, String intent, Instant expiresAt) {
        jdbc.update("""
                UPDATE conversations
                SET clarification_pending=TRUE, clarification_intent=?, clarification_expires_at=?
                WHERE id=?
                """, intent, expiresAt == null ? null : java.sql.Timestamp.from(expiresAt), conversationId);
    }

    public void clearClarification(long conversationId) {
        jdbc.update("""
                UPDATE conversations
                SET clarification_pending=FALSE, clarification_intent=NULL, clarification_expires_at=NULL
                WHERE id=?
                """, conversationId);
    }

    public SummaryState summaryState(long conversationId) {
        String sql = encKey.isBlank()
                ? "SELECT summary, COALESCE(summary_upto_msg_id,0) FROM conversations WHERE id=?"
                : "SELECT COALESCE(pgp_sym_decrypt(summary_encrypted, ?), summary), COALESCE(summary_upto_msg_id,0) FROM conversations WHERE id=?";
        List<SummaryState> rows = encKey.isBlank()
                ? jdbc.query(sql, (rs, i) -> new SummaryState(rs.getString(1), rs.getLong(2)), conversationId)
                : jdbc.query(sql, (rs, i) -> new SummaryState(rs.getString(1), rs.getLong(2)), encKey, conversationId);
        return rows.stream().findFirst().orElse(new SummaryState(null, 0));
    }

    public long episodeUptoMsgId(long conversationId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(episode_upto_msg_id,0) FROM conversations WHERE id=?", Long.class, conversationId);
        return value == null ? 0 : value;
    }

    public List<Msg> messagesAfter(long conversationId, long messageId, int limit) {
        String sql = encKey.isBlank()
                ? "SELECT id, role, content FROM messages WHERE conversation_id=? AND id>? ORDER BY id LIMIT ?"
                : "SELECT id, role, COALESCE(pgp_sym_decrypt(content_encrypted, ?), content) FROM messages WHERE conversation_id=? AND id>? ORDER BY id LIMIT ?";
        return encKey.isBlank()
                ? jdbc.query(sql, (rs, i) -> new Msg(rs.getLong(1), rs.getString(2), rs.getString(3)), conversationId, messageId, limit)
                : jdbc.query(sql, (rs, i) -> new Msg(rs.getLong(1), rs.getString(2), rs.getString(3)), encKey, conversationId, messageId, limit);
    }

    public void advanceEpisodeWatermark(long conversationId, long messageId) {
        jdbc.update("UPDATE conversations SET episode_upto_msg_id=GREATEST(COALESCE(episode_upto_msg_id,0), ?) WHERE id=?", messageId, conversationId);
    }

    /** 待折叠消息: 已被摘要覆盖之后、且不在最近keepRecent条窗口内的原文 (时间正序) */
    public List<Msg> messagesToFold(long conversationId, long uptoMsgId, int keepRecent) {
        String text = encKey.isBlank() ? "content" : "COALESCE(pgp_sym_decrypt(content_encrypted, ?), content)";
        String sql = """
                SELECT role, %s FROM messages
                WHERE conversation_id = ? AND id > ?
                  AND id <= (SELECT COALESCE(MIN(id),0) - 1 FROM (
                        SELECT id FROM messages WHERE conversation_id = ? ORDER BY id DESC LIMIT ?) recent)
                ORDER BY id
                """.formatted(text);
        return encKey.isBlank()
                ? jdbc.query(sql, (rs, i) -> new Msg(rs.getString(1), rs.getString(2)),
                conversationId, uptoMsgId, conversationId, keepRecent)
                : jdbc.query(sql, (rs, i) -> new Msg(rs.getString(1), rs.getString(2)),
                encKey, conversationId, uptoMsgId, conversationId, keepRecent);
    }

    public long maxFoldableMsgId(long conversationId, int keepRecent) {
        Long v = jdbc.queryForObject("""
                SELECT COALESCE(MIN(id),0) - 1 FROM (
                    SELECT id FROM messages WHERE conversation_id = ? ORDER BY id DESC LIMIT ?) recent
                """, Long.class, conversationId, keepRecent);
        return v == null ? 0 : v;
    }

    public void saveSummary(long conversationId, String summary, long uptoMsgId) {
        if (encKey.isBlank()) {
            jdbc.update("UPDATE conversations SET summary=?, summary_upto_msg_id=? WHERE id=?",
                    summary, uptoMsgId, conversationId);
        } else {
            jdbc.update("""
                    UPDATE conversations SET summary=?, summary_encrypted=pgp_sym_encrypt(?, ?), summary_upto_msg_id=? WHERE id=?
                    """, PiiMasker.mask(summary).masked(), summary, encKey, uptoMsgId, conversationId);
        }
    }

    public boolean saveSummaryIfGeneration(long conversationId, long userId, long generation,
                                           String summary, long uptoMsgId) {
        if (encKey.isBlank()) return jdbc.update("""
                UPDATE conversations SET summary=?, summary_upto_msg_id=?
                WHERE id=? AND user_id=?
                  AND EXISTS (SELECT 1 FROM users WHERE id=? AND memory_generation=?)
                """, summary, uptoMsgId, conversationId, userId, userId, generation) > 0;
        return jdbc.update("""
                UPDATE conversations SET summary=?, summary_encrypted=pgp_sym_encrypt(?, ?), summary_upto_msg_id=?
                WHERE id=? AND user_id=?
                  AND EXISTS (SELECT 1 FROM users WHERE id=? AND memory_generation=?)
                """, PiiMasker.mask(summary).masked(), summary, encKey, uptoMsgId,
                conversationId, userId, userId, generation) > 0;
    }
}
