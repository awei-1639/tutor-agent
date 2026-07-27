package com.tutor.memory;

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

    public record Msg(String role, String content) {}

    public long ensureConversation(Long conversationId, long userId) {
        if (conversationId != null) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM conversations WHERE id=?", Integer.class, conversationId);
            if (n != null && n > 0) {
                jdbc.update("UPDATE conversations SET last_active_at=now() WHERE id=?", conversationId);
                return conversationId;
            }
        }
        jdbc.update("INSERT INTO users (id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        return jdbc.queryForObject(
                "INSERT INTO conversations (user_id, last_active_at) VALUES (?, now()) RETURNING id",
                Long.class, userId);
    }

    public long appendMessage(long conversationId, String role, String content,
                              String intent, String citationsJson, int tokenCount) {
        return jdbc.queryForObject(
                "INSERT INTO messages (conversation_id, role, content, intent, citations, token_count) " +
                        "VALUES (?,?,?,?,?::jsonb,?) RETURNING id",
                Long.class, conversationId, role, content, intent, citationsJson, tokenCount);
    }

    /** 最近N轮原文, 时间正序 */
    public List<Msg> recentMessages(long conversationId, int limit) {
        List<Msg> desc = jdbc.query(
                "SELECT role, content FROM messages WHERE conversation_id=? ORDER BY id DESC LIMIT ?",
                (rs, i) -> new Msg(rs.getString(1), rs.getString(2)), conversationId, limit);
        return desc.reversed();
    }

    public record SummaryState(String summary, long uptoMsgId) {}

    public SummaryState summaryState(long conversationId) {
        return jdbc.query("SELECT summary, COALESCE(summary_upto_msg_id,0) FROM conversations WHERE id=?",
                        (rs, i) -> new SummaryState(rs.getString(1), rs.getLong(2)), conversationId)
                .stream().findFirst().orElse(new SummaryState(null, 0));
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
