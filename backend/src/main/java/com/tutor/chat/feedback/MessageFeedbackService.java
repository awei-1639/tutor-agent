package com.tutor.chat.feedback;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** 保存用户对单条回答的反馈；插入时以会话归属作为授权条件。 */
@Service
public class MessageFeedbackService {
    private final JdbcTemplate jdbc;

    public MessageFeedbackService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Feedback(long id, long messageId, String rating, String reason, String traceId) {}
    public record ReasonCount(String reason, long count) {}
    public record TraceFeedback(String traceId, long messageId, String reason, Instant createdAt) {}
    public record Summary(long total, long helpful, long notHelpful,
                          List<ReasonCount> reasons, List<TraceFeedback> latestNotHelpful) {}

    public Feedback save(long userId, long messageId, String rating, String reason) {
        return jdbc.query("""
                INSERT INTO message_feedback (user_id, conversation_id, message_id, trace_id, rating, reason)
                SELECT c.user_id, m.conversation_id, m.id, m.trace_id, ?, ?
                FROM messages m JOIN conversations c ON c.id=m.conversation_id
                WHERE m.id=? AND m.role='assistant' AND c.user_id=?
                ON CONFLICT (user_id, message_id) DO UPDATE
                  SET rating=EXCLUDED.rating, reason=EXCLUDED.reason, updated_at=now()
                RETURNING id, message_id, rating, reason, trace_id
                """, (rs, i) -> new Feedback(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)), rating, reason, messageId, userId)
                .stream().findFirst().orElse(null);
    }

    /** 仅供 /internal 评测面板使用，不暴露回答正文或用户身份。 */
    public Summary summary() {
        long[] totals = jdbc.queryForObject("""
                SELECT count(*),
                       count(*) FILTER (WHERE rating='helpful'),
                       count(*) FILTER (WHERE rating='not_helpful')
                FROM message_feedback
                """, (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)});
        List<ReasonCount> reasons = jdbc.query("""
                SELECT COALESCE(reason, 'unspecified'), count(*)
                FROM message_feedback WHERE rating='not_helpful'
                GROUP BY COALESCE(reason, 'unspecified') ORDER BY count(*) DESC, 1
                """, (rs, i) -> new ReasonCount(rs.getString(1), rs.getLong(2)));
        List<TraceFeedback> latest = jdbc.query("""
                SELECT COALESCE(trace_id, ''), message_id, COALESCE(reason, 'unspecified'), created_at
                FROM message_feedback WHERE rating='not_helpful'
                ORDER BY created_at DESC LIMIT 20
                """, (rs, i) -> new TraceFeedback(rs.getString(1), rs.getLong(2), rs.getString(3),
                rs.getTimestamp(4).toInstant()));
        return new Summary(totals[0], totals[1], totals[2], reasons, latest);
    }
}
