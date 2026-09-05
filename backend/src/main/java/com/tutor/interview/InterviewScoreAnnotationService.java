package com.tutor.interview;

import com.tutor.identity.admin.AdminService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Admin-only human calibration labels; answer text is only exposed in the de-identified review queue. */
@Service
public class InterviewScoreAnnotationService {
    private final JdbcTemplate jdbc;
    private final AdminService admins;

    public InterviewScoreAnnotationService(JdbcTemplate jdbc, AdminService admins) {
        this.jdbc = jdbc;
        this.admins = admins;
    }

    @Transactional
    public Map<String, Object> upsert(long questionId, int humanScore, String rationale) {
        long reviewerId = admins.requireAdmin();
        if (humanScore < 0 || humanScore > 10) throw new IllegalArgumentException("人工分数必须在 0 到 10 之间");
        Integer exists = jdbc.queryForObject("""
                SELECT count(*)
                FROM interview_questions q
                JOIN interview_sessions s ON s.id=q.session_id
                WHERE q.id=? AND q.answer IS NOT NULL AND q.score IS NOT NULL
                  AND s.status IN ('COMPLETED', 'CANCELLED')
                """, Integer.class, questionId);
        if (exists == null || exists == 0) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "面试题不存在、尚未评分或尚未收卷");
        jdbc.update("""
                INSERT INTO interview_score_annotations (question_id, reviewer_id, human_score, rationale)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (question_id, reviewer_id) DO UPDATE SET human_score=EXCLUDED.human_score,
                  rationale=EXCLUDED.rationale, updated_at=now()
                """, questionId, reviewerId, humanScore, rationale == null ? "" : rationale.trim());
        admins.auditEvent(reviewerId, "INTERVIEW_SCORE_ANNOTATED", "{\"questionId\":" + questionId + "}");
        return get(questionId, reviewerId);
    }

    public List<Map<String, Object>> list(int limit) {
        admins.requireAdmin();
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return jdbc.query("""
                SELECT a.id, a.question_id, q.score AS model_score,
                       COALESCE((q.scorecard->>'confidence')::numeric, 0) AS model_confidence,
                       a.reviewer_id, a.human_score, a.rationale, a.created_at, a.updated_at
                FROM interview_score_annotations a
                JOIN interview_questions q ON q.id=a.question_id
                ORDER BY a.updated_at DESC LIMIT ?
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong(1));
            row.put("questionId", rs.getLong(2));
            row.put("modelScore", rs.getObject(3));
            row.put("modelConfidence", rs.getDouble(4));
            row.put("reviewerId", rs.getLong(5));
            row.put("humanScore", rs.getInt(6));
            row.put("rationale", rs.getString(7));
            row.put("createdAt", rs.getTimestamp(8).toInstant());
            row.put("updatedAt", rs.getTimestamp(9).toInstant());
            return row;
        }, safeLimit);
    }

    /**
     * Returns de-identified, answered questions that still need independent
     * labels. The queue deliberately omits user/session identifiers and only
     * exposes the answer to an authenticated administrator for review. The
     * controller uses blind mode so model and feedback signals cannot anchor
     * the human score before submission.
     */
    public List<Map<String, Object>> queue(int limit, int minReviewers) {
        return queue(limit, minReviewers, false, 1);
    }

    public List<Map<String, Object>> queue(int limit, int minReviewers, boolean blind) {
        return queue(limit, minReviewers, blind, 1);
    }

    public List<Map<String, Object>> queue(int limit, int minReviewers, boolean blind, int maxPerSession) {
        long reviewerId = admins.requireAdmin();
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        int requiredReviewers = Math.min(Math.max(minReviewers, 1), 5);
        int perSession = Math.min(Math.max(maxPerSession, 1), 10);
        List<Map<String, Object>> rows = jdbc.query("""
                WITH candidates AS (
                    SELECT q.id, q.session_id, q.prompt, q.answer, q.score,
                           COALESCE((q.scorecard->>'confidence')::numeric, 0) AS model_confidence,
                           COUNT(a.id) AS reviewer_count, q.answered_at,
                           COALESCE(f.rating, '') AS feedback_rating,
                           COALESCE(f.reason, '') AS feedback_reason,
                           CASE WHEN f.rating='inaccurate' THEN 0
                                WHEN COALESCE((q.scorecard->>'confidence')::numeric, 0) < 0.6 THEN 1
                                ELSE 2 END AS priority
                    FROM interview_questions q
                    JOIN interview_sessions s ON s.id=q.session_id
                    LEFT JOIN interview_score_annotations a ON a.question_id=q.id
                    LEFT JOIN interview_feedback f ON f.session_id=q.session_id
                    WHERE q.answer IS NOT NULL AND q.score IS NOT NULL
                      AND s.status IN ('COMPLETED', 'CANCELLED')
                      AND NOT EXISTS (
                        SELECT 1 FROM interview_score_annotations mine
                        WHERE mine.question_id=q.id AND mine.reviewer_id=?
                      )
                    GROUP BY q.id, q.session_id, q.prompt, q.answer, q.score, q.scorecard,
                             q.answered_at, f.rating, f.reason
                    HAVING COUNT(a.id) < ?
                ), ranked AS (
                    SELECT candidates.*,
                           ROW_NUMBER() OVER (
                             PARTITION BY session_id
                             ORDER BY priority ASC, answered_at DESC NULLS LAST, id DESC
                           ) AS session_rank
                    FROM candidates
                )
                SELECT id, prompt, answer, score, model_confidence, reviewer_count, answered_at,
                       feedback_rating, feedback_reason, priority
                FROM ranked
                WHERE session_rank <= ?
                ORDER BY priority ASC, answered_at DESC NULLS LAST, id DESC
                LIMIT ?
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("questionId", rs.getLong("id"));
            row.put("prompt", deidentify(rs.getString("prompt")));
            row.put("answer", deidentify(rs.getString("answer")));
            row.put("modelScore", blind ? null : rs.getInt("score"));
            row.put("modelConfidence", blind ? null : rs.getDouble("model_confidence"));
            row.put("reviewerCount", rs.getInt("reviewer_count"));
            row.put("answeredAt", rs.getTimestamp("answered_at") == null ? null : rs.getTimestamp("answered_at").toInstant());
            row.put("feedbackRating", blind ? null : rs.getString("feedback_rating"));
            row.put("feedbackReason", blind ? null : deidentify(rs.getString("feedback_reason")));
            row.put("priority", blind ? null : rs.getInt("priority"));
            return row;
        }, reviewerId, requiredReviewers, perSession, safeLimit);
        admins.auditEvent(reviewerId, "INTERVIEW_SCORE_QUEUE_VIEWED",
                "{\"count\":" + rows.size() + ",\"minReviewers\":" + requiredReviewers
                        + ",\"maxPerSession\":" + perSession + ",\"blind\":" + blind + "}");
        return rows;
    }

    public InterviewScoreEvalService.ReplayRequest exportReplay(String datasetVersion, int minReviewers) {
        long reviewerId = admins.requireAdmin();
        int requiredReviewers = Math.min(Math.max(minReviewers, 1), 5);
        List<InterviewScoreEvalService.ReplayCase> cases = jdbc.query("""
                SELECT q.id, q.score, COALESCE((q.scorecard->>'confidence')::numeric, 0),
                       ROUND(AVG(a.human_score)), COUNT(*), MIN(a.human_score), MAX(a.human_score)
                FROM interview_questions q
                JOIN interview_sessions s ON s.id=q.session_id
                JOIN interview_score_annotations a ON a.question_id=q.id
                WHERE q.score IS NOT NULL AND s.status IN ('COMPLETED', 'CANCELLED')
                GROUP BY q.id, q.score, q.scorecard
                HAVING COUNT(*) >= ?
                ORDER BY q.id
                """, (rs, i) -> new InterviewScoreEvalService.ReplayCase(rs.getString(1), rs.getInt(4),
                rs.getInt(2), rs.getDouble(3), rs.getInt(5), rs.getInt(7) - rs.getInt(6)), requiredReviewers);
        if (cases.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "没有满足最少评审人数的标注样本");
        admins.auditEvent(reviewerId, "INTERVIEW_SCORE_REPLAY_EXPORTED",
                "{\"caseCount\":" + cases.size() + ",\"minReviewers\":" + requiredReviewers + "}");
        return new InterviewScoreEvalService.ReplayRequest(datasetVersion, cases);
    }

    private Map<String, Object> get(long questionId, long reviewerId) {
        return jdbc.queryForObject("""
                SELECT id, question_id, human_score, rationale, created_at, updated_at
                FROM interview_score_annotations WHERE question_id=? AND reviewer_id=?
                """, (rs, i) -> Map.of("id", rs.getLong(1), "questionId", rs.getLong(2),
                "humanScore", rs.getInt(3), "rationale", rs.getString(4),
                "createdAt", rs.getTimestamp(5).toInstant(), "updatedAt", rs.getTimestamp(6).toInstant()), questionId, reviewerId);
    }

    private String deidentify(String value) {
        if (value == null || value.isBlank()) return "";
        String redacted = value
                .replaceAll("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", "[email]")
                .replaceAll("(?<!\\d)(?:\\+?86[- ]?)?1\\d{10}(?!\\d)", "[phone]");
        return redacted.length() <= 12000 ? redacted : redacted.substring(0, 12000) + "…";
    }
}
