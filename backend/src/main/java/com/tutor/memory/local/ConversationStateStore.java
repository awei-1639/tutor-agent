package com.tutor.memory.local;

import com.tutor.resume.PiiMasker;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

/** Owns clarification, summary, and episode-watermark persistence. */
final class ConversationStateStore {
    private final JdbcTemplate jdbc;
    private final String encKey;

    ConversationStateStore(JdbcTemplate jdbc, String encKey) {
        this.jdbc = jdbc;
        this.encKey = encKey == null ? "" : encKey;
    }

    public ConversationStore.ClarificationState clarificationState(long conversationId) {
        List<ConversationStore.ClarificationState> rows = jdbc.query("""
                SELECT clarification_pending, clarification_intent, clarification_expires_at
                FROM conversations WHERE id=?
                """, (rs, i) -> new ConversationStore.ClarificationState(rs.getBoolean(1), rs.getString(2),
                rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()), conversationId);
        ConversationStore.ClarificationState state = rows.stream().findFirst().orElse(ConversationStore.ClarificationState.none());
        if (state.pending() && state.expiresAt() != null && state.expiresAt().isBefore(Instant.now())) {
            clearClarification(conversationId);
            return ConversationStore.ClarificationState.none();
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

    public ConversationStore.SummaryState summaryState(long conversationId) {
        String sql = encKey.isBlank()
                ? "SELECT summary, COALESCE(summary_upto_msg_id,0) FROM conversations WHERE id=?"
                : "SELECT COALESCE(pgp_sym_decrypt(summary_encrypted, ?), summary), COALESCE(summary_upto_msg_id,0) FROM conversations WHERE id=?";
        List<ConversationStore.SummaryState> rows = encKey.isBlank()
                ? jdbc.query(sql, (rs, i) -> new ConversationStore.SummaryState(rs.getString(1), rs.getLong(2)), conversationId)
                : jdbc.query(sql, (rs, i) -> new ConversationStore.SummaryState(rs.getString(1), rs.getLong(2)), encKey, conversationId);
        return rows.stream().findFirst().orElse(new ConversationStore.SummaryState(null, 0));
    }

    public long episodeUptoMsgId(long conversationId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(episode_upto_msg_id,0) FROM conversations WHERE id=?", Long.class, conversationId);
        return value == null ? 0 : value;
    }

    public List<ConversationStore.Msg> messagesAfter(long conversationId, long messageId, int limit) {
        String sql = encKey.isBlank()
                ? "SELECT id, role, content FROM messages WHERE conversation_id=? AND id>? ORDER BY id LIMIT ?"
                : "SELECT id, role, COALESCE(pgp_sym_decrypt(content_encrypted, ?), content) FROM messages WHERE conversation_id=? AND id>? ORDER BY id LIMIT ?";
        return encKey.isBlank()
                ? jdbc.query(sql, (rs, i) -> new ConversationStore.Msg(rs.getLong(1), rs.getString(2), rs.getString(3)), conversationId, messageId, limit)
                : jdbc.query(sql, (rs, i) -> new ConversationStore.Msg(rs.getLong(1), rs.getString(2), rs.getString(3)), encKey, conversationId, messageId, limit);
    }

    public void advanceEpisodeWatermark(long conversationId, long messageId) {
        jdbc.update("UPDATE conversations SET episode_upto_msg_id=GREATEST(COALESCE(episode_upto_msg_id,0), ?) WHERE id=?", messageId, conversationId);
    }

    public List<ConversationStore.Msg> messagesToFold(long conversationId, long uptoMsgId, int keepRecent) {
        String text = encKey.isBlank() ? "content" : "COALESCE(pgp_sym_decrypt(content_encrypted, ?), content)";
        String sql = """
                SELECT role, %s FROM messages
                WHERE conversation_id = ? AND id > ?
                  AND id <= (SELECT COALESCE(MIN(id),0) - 1 FROM (
                        SELECT id FROM messages WHERE conversation_id = ? ORDER BY id DESC LIMIT ?) recent)
                ORDER BY id
                """.formatted(text);
        return encKey.isBlank()
                ? jdbc.query(sql, (rs, i) -> new ConversationStore.Msg(rs.getString(1), rs.getString(2)),
                conversationId, uptoMsgId, conversationId, keepRecent)
                : jdbc.query(sql, (rs, i) -> new ConversationStore.Msg(rs.getString(1), rs.getString(2)),
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
