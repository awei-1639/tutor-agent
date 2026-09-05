package com.tutor.conversation.memory.local;

import com.tutor.identity.resume.PiiMasker;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** Owns conversation and message persistence, including encrypted message reads and writes. */
final class ConversationMessageStore {
    private final JdbcTemplate jdbc;
    private final String encKey;

    ConversationMessageStore(JdbcTemplate jdbc, String encKey) {
        this.jdbc = jdbc;
        this.encKey = encKey == null ? "" : encKey;
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

    public long appendMessage(long conversationId, String role, String content,
                              String intent, String citationsJson, String traceId, int tokenCount,
                              String citationStatus, String citationIssuesJson) {
        return appendMessage(conversationId, role, content, intent, citationsJson, traceId, tokenCount,
                citationStatus, citationIssuesJson, null);
    }

    public long appendMessage(long conversationId, String role, String content,
                              String intent, String citationsJson, String traceId, int tokenCount,
                              String citationStatus, String citationIssuesJson, String chatTurnId) {
        citationStatus = citationStatus == null || citationStatus.isBlank() ? "not_applicable" : citationStatus;
        if (!encKey.isBlank()) {
            String masked = PiiMasker.mask(content).masked();
            return jdbc.queryForObject("""
                    INSERT INTO messages (conversation_id, role, content, content_encrypted, intent, citations, trace_id, token_count,
                                          citation_status, citation_issues, chat_turn_id)
                    VALUES (?,?,?,pgp_sym_encrypt(?,?),?,?::jsonb,?,?,?,?::jsonb,?::uuid) RETURNING id
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

    public List<ConversationStore.Msg> recentMessages(long conversationId, int limit) {
        String sql = encKey.isBlank()
                ? "SELECT id, role, content, citations, trace_id, citation_status, citation_issues FROM messages WHERE conversation_id=? ORDER BY id DESC LIMIT ?"
                : "SELECT id, role, COALESCE(pgp_sym_decrypt(content_encrypted, ?), content), citations, trace_id, citation_status, citation_issues FROM messages WHERE conversation_id=? ORDER BY id DESC LIMIT ?";
        List<ConversationStore.Msg> desc = encKey.isBlank() ? jdbc.query(sql,
                (rs, i) -> {
                    String citations = rs.getString(4);
                    ConversationStore.Msg m = new ConversationStore.Msg(rs.getLong(1), rs.getString(2), rs.getString(3));
                    m.citations = citations; // 字符串 JSON, 前端按 string 处理
                    m.traceId = rs.getString(5);
                    m.citationStatus = rs.getString(6);
                    m.citationIssues = rs.getString(7);
                    return m;
                }, conversationId, limit) : jdbc.query(sql,
                (rs, i) -> {
                    String citations = rs.getString(4);
                    ConversationStore.Msg m = new ConversationStore.Msg(rs.getLong(1), rs.getString(2), rs.getString(3));
                    m.citations = citations;
                    m.traceId = rs.getString(5);
                    m.citationStatus = rs.getString(6);
                    m.citationIssues = rs.getString(7);
                    return m;
                }, encKey, conversationId, limit);
        return desc.reversed();
    }

    public List<ConversationStore.Msg> recentMessagesForUser(long conversationId, long userId, int limit) {
        String sql = """
                SELECT m.id, m.role, %s, m.citations, m.trace_id, mf.rating, m.citation_status, m.citation_issues
                FROM messages m JOIN conversations c ON c.id=m.conversation_id
                LEFT JOIN message_feedback mf ON mf.message_id=m.id AND mf.user_id=?
                WHERE m.conversation_id=? AND c.user_id=?
                ORDER BY m.id DESC LIMIT ?
                """.formatted(encKey.isBlank() ? "m.content" : "COALESCE(pgp_sym_decrypt(m.content_encrypted, ?), m.content)");
        List<ConversationStore.Msg> desc = encKey.isBlank() ? jdbc.query(sql, (rs, i) -> {
                    ConversationStore.Msg m = new ConversationStore.Msg(rs.getLong(1), rs.getString(2), rs.getString(3));
                    m.citations = rs.getString(4);
                    m.traceId = rs.getString(5);
                    m.feedback = rs.getString(6);
                    m.citationStatus = rs.getString(7);
                    m.citationIssues = rs.getString(8);
                    return m;
                }, userId, conversationId, userId, limit) : jdbc.query(sql, (rs, i) -> {
                    ConversationStore.Msg m = new ConversationStore.Msg(rs.getLong(1), rs.getString(2), rs.getString(3));
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

    public boolean memoryGenerationCurrent(long conversationId, long userId, long generation) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM conversations c
                JOIN users u ON u.id=c.user_id
                WHERE c.id=? AND c.user_id=? AND u.memory_generation=?
                """, Integer.class, conversationId, userId, generation);
        return count != null && count > 0;
    }

    public boolean deleteConversationForUser(long conversationId, long userId) {
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM conversations WHERE id=? AND user_id=?",
                Integer.class, conversationId, userId);
        if (exists == null || exists == 0) return false;
        jdbc.update("DELETE FROM messages WHERE conversation_id=?", conversationId);
        jdbc.update("DELETE FROM conversations WHERE id=? AND user_id=?", conversationId, userId);
        return true;
    }

    public void deleteAllForUser(long userId) {
        jdbc.update("DELETE FROM messages WHERE conversation_id IN (SELECT id FROM conversations WHERE user_id=?)", userId);
        jdbc.update("DELETE FROM conversations WHERE user_id=?", userId);
    }

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
}
