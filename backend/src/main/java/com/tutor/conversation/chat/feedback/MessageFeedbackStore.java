package com.tutor.conversation.chat.feedback;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** SQL boundary for user feedback and internal attribution aggregates. */
@Repository
public class MessageFeedbackStore {
    private final JdbcTemplate jdbc;

    public MessageFeedbackStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    MessageFeedbackService.Feedback save(long userId, long messageId, String rating, String reason) {
        return jdbc.query("""
                INSERT INTO message_feedback (user_id, conversation_id, message_id, trace_id, rating, reason)
                SELECT c.user_id, m.conversation_id, m.id, m.trace_id, ?, ?
                FROM messages m JOIN conversations c ON c.id=m.conversation_id
                WHERE m.id=? AND m.role='assistant' AND c.user_id=?
                ON CONFLICT (user_id, message_id) DO UPDATE
                  SET rating=EXCLUDED.rating, reason=EXCLUDED.reason, updated_at=now()
                RETURNING id, message_id, rating, reason, trace_id
                """, (rs, i) -> new MessageFeedbackService.Feedback(rs.getLong(1), rs.getLong(2),
                rs.getString(3), rs.getString(4), rs.getString(5)), rating, reason, messageId, userId)
                .stream().findFirst().orElse(null);
    }

    long[] totals() {
        return jdbc.queryForObject("""
                SELECT count(*), count(*) FILTER (WHERE rating='helpful'), count(*) FILTER (WHERE rating='not_helpful')
                FROM message_feedback
                """, (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)});
    }

    List<MessageFeedbackService.ReasonCount> reasons() {
        return jdbc.query("""
                SELECT COALESCE(reason, 'unspecified'), count(*) FROM message_feedback
                WHERE rating='not_helpful' GROUP BY COALESCE(reason, 'unspecified') ORDER BY count(*) DESC, 1
                """, (rs, i) -> new MessageFeedbackService.ReasonCount(rs.getString(1), rs.getLong(2)));
    }

    List<MessageFeedbackService.TraceFeedback> latestNotHelpful() {
        return jdbc.query("""
                SELECT COALESCE(trace_id, ''), message_id, COALESCE(reason, 'unspecified'), created_at
                FROM message_feedback WHERE rating='not_helpful' ORDER BY created_at DESC LIMIT 20
                """, (rs, i) -> new MessageFeedbackService.TraceFeedback(rs.getString(1), rs.getLong(2),
                rs.getString(3), rs.getTimestamp(4).toInstant()));
    }

    List<MessageFeedbackService.Attribution> attributions() {
        return jdbc.query("""
                WITH negative_feedback AS (
                    SELECT f.trace_id, f.reason, m.citation_status FROM message_feedback f
                    JOIN messages m ON m.id=f.message_id WHERE f.rating='not_helpful' AND f.trace_id IS NOT NULL
                ), router_trace AS (
                    SELECT DISTINCT ON (trace_id) trace_id, snapshot FROM turn_traces WHERE node='router' ORDER BY trace_id, id DESC
                ), retrieval_trace AS (
                    SELECT DISTINCT ON (trace_id) trace_id, snapshot FROM turn_traces WHERE node='retrieve' ORDER BY trace_id, id DESC
                )
                SELECT COALESCE(router_trace.snapshot->>'retrieval_facets', '[]'),
                       COALESCE(retrieval_trace.snapshot->>'requested_mode', 'unavailable'),
                       COALESCE((retrieval_trace.snapshot->>'hops')::int, 0),
                       COALESCE(retrieval_trace.snapshot->>'retrieval_profile_version', 'unavailable'),
                       COALESCE((retrieval_trace.snapshot->>'final_graph_evidence_count')::bigint, 0),
                       COALESCE((retrieval_trace.snapshot->>'final_direct_evidence_count')::bigint, 0),
                       COALESCE((retrieval_trace.snapshot->>'dense_candidate_count')::bigint, 0),
                       COALESCE((retrieval_trace.snapshot->>'sparse_candidate_count')::bigint, 0),
                       COALESCE((retrieval_trace.snapshot->>'graph_candidate_count')::bigint, 0),
                       COALESCE((retrieval_trace.snapshot->>'graph_expansion_source_count')::bigint, 0),
                       COALESCE((retrieval_trace.snapshot->>'embedding_degraded')::boolean, false),
                       COALESCE((retrieval_trace.snapshot->>'sparse_degraded')::boolean, false),
                       COALESCE((retrieval_trace.snapshot->>'rerank_applied')::boolean, false),
                       COALESCE((retrieval_trace.snapshot->>'rerank_degraded')::boolean, false),
                       COALESCE(negative_feedback.citation_status, 'unavailable'),
                       COALESCE(negative_feedback.reason, 'unspecified'), count(*)
                FROM negative_feedback LEFT JOIN router_trace ON router_trace.trace_id=negative_feedback.trace_id
                LEFT JOIN retrieval_trace ON retrieval_trace.trace_id=negative_feedback.trace_id
                GROUP BY 1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16
                ORDER BY count(*) DESC, 1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16 LIMIT 100
                """, (rs, i) -> new MessageFeedbackService.Attribution(rs.getString(1), rs.getString(2), rs.getInt(3),
                rs.getString(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9),
                rs.getLong(10), rs.getBoolean(11), rs.getBoolean(12), rs.getBoolean(13), rs.getBoolean(14),
                rs.getString(15), rs.getString(16), rs.getLong(17)));
    }
}
