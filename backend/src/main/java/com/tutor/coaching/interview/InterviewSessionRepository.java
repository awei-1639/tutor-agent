package com.tutor.coaching.interview;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SQL boundary for the state transitions that make an interview durable. */
@Repository
class InterviewSessionRepository {
    private final JdbcTemplate jdbc;

    InterviewSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insertQuestion(String sessionId, int sequence, String kind, String prompt, String skillId, String contract) {
        jdbc.update("""
                INSERT INTO interview_questions (session_id, sequence, kind, prompt, skill_id, assessment_contract)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """, sessionId, sequence, kind, prompt, skillId, contract);
    }

    void insertBlueprint(String id, long userId, String role, String topic, List<String> skills,
                         String roundPlan, String jobDescription, String interviewType,
                         String difficulty, int durationMinutes) {
        jdbc.update("""
                INSERT INTO interview_blueprints
                  (id, user_id, target_role, topic, skill_ids, round_plan, job_description, interview_type, difficulty, duration_minutes)
                VALUES (?, ?, ?, ?, ?::text[], ?::jsonb, ?, ?, ?, ?)
                """, id, userId, role, topic, toTextArray(skills), roundPlan,
                jobDescription, interviewType, difficulty, durationMinutes);
    }

    void insertSession(String id, long userId, String role, String topic, String blueprintId,
                       List<String> skills, String retestOf, int durationMinutes) {
        jdbc.update("""
                INSERT INTO interview_sessions
                  (id, user_id, target_role, topic, status, current_question_sequence, main_question_count, blueprint_id, skill_ids, retest_of, deadline_at)
                VALUES (?, ?, ?, ?, 'IN_PROGRESS', 1, 1, ?, ?::text[], ?, now() + (? * interval '1 minute'))
                """, id, userId, role, topic, blueprintId, toTextArray(skills), retestOf, durationMinutes);
    }

    List<InterviewSession.TranscriptTurn> transcript(String sessionId) {
        List<InterviewSession.TranscriptTurn> transcript = new ArrayList<>();
        jdbc.query("""
                SELECT prompt, answer, kind, COALESCE(scorecard->>'reply', ''), COALESCE(scorecard->>'tip', '')
                FROM interview_questions
                WHERE session_id=? ORDER BY sequence
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            transcript.add(new InterviewSession.TranscriptTurn("ai", rs.getString(1)));
            String answer = rs.getString(2);
            if (answer != null) transcript.add(new InterviewSession.TranscriptTurn("me", answer));
            // 反问环节: 面试官的回答不在下一题的 prompt 里, 单独补回时间线, 避免刷新后只剩提问。
            if ("CANDIDATE_ASK".equals(rs.getString(3)) && answer != null) {
                String reply = rs.getString(4);
                if (!reply.isBlank()) {
                    String tip = rs.getString(5);
                    transcript.add(new InterviewSession.TranscriptTurn("ai",
                            tip.isBlank() ? reply : reply + "\n\n💡 反问点评: " + tip));
                }
            }
        }, sessionId);
        return transcript;
    }

    List<InterviewSession.HistoryItem> history(long userId, int limit) {
        return jdbc.query("""
                SELECT s.id, s.target_role, COALESCE(b.interview_type, 'technical'), COALESCE(b.difficulty, 'MID'),
                       s.status, count(q.id), COALESCE(avg(q.score), 0), s.created_at, s.completed_at, s.retest_of,
                       CASE WHEN s.status='COMPLETED' AND s.retest_of IS NOT NULL THEN source.avg_score END
                FROM interview_sessions s
                LEFT JOIN interview_blueprints b ON b.id=s.blueprint_id
                LEFT JOIN interview_questions q ON q.session_id=s.id AND q.score IS NOT NULL
                LEFT JOIN LATERAL (
                    SELECT avg(score) AS avg_score FROM interview_questions WHERE session_id=s.retest_of AND score IS NOT NULL
                ) source ON true
                WHERE s.user_id=?
                GROUP BY s.id, b.interview_type, b.difficulty, source.avg_score
                ORDER BY s.created_at DESC
                LIMIT ?
                """, (rs, i) -> {
            double sourceAverage = rs.getDouble(11);
            Double delta = rs.wasNull() ? null : rs.getDouble(7) - sourceAverage;
            return new InterviewSession.HistoryItem(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getInt(6), rs.getDouble(7),
                    rs.getTimestamp(8).toInstant(), rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant(),
                    rs.getString(10), delta);
        }, userId, limit);
    }

    void recordFeedback(long userId, String sessionId, String rating, String reason) {
        jdbc.update("""
                INSERT INTO interview_feedback (user_id, session_id, rating, reason)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, session_id) DO UPDATE SET rating=EXCLUDED.rating, reason=EXCLUDED.reason, updated_at=now()
                """, userId, sessionId, rating, reason == null ? "" : reason.trim());
    }

    List<String> requiredSkillsForRole(String targetRole) {
        return jdbc.query("SELECT unnest(requires_raw) FROM jobs WHERE title LIKE ? LIMIT 3",
                (rs, i) -> rs.getString(1), "%" + targetRole + "%");
    }

    List<String> priorPrompts(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        return jdbc.query("SELECT prompt FROM interview_questions WHERE session_id=? ORDER BY sequence",
                (rs, i) -> rs.getString(1), sessionId);
    }

    List<InterviewSession.QuestionScore> scoredQuestions(String sessionId) {
        return jdbc.query("""
                SELECT prompt, score, COALESCE(scorecard::text, '{}') FROM interview_questions
                WHERE session_id=? AND score IS NOT NULL ORDER BY sequence
                """, (rs, i) -> new InterviewSession.QuestionScore(rs.getString(1), rs.getInt(2), rs.getString(3)),
                sessionId);
    }

    /** 低分题关联的技能 (由弱到强), 供面试后生成针对性学习任务。 */
    List<String> weakSkills(String sessionId, int threshold) {
        return jdbc.query("""
                SELECT skill_id FROM interview_questions
                WHERE session_id=? AND score IS NOT NULL AND score < ? AND skill_id IS NOT NULL
                ORDER BY score ASC LIMIT 5
                """, (rs, i) -> rs.getString(1), sessionId, threshold);
    }

    /**
     * 当前问题所处的追问层: 主线题为 0, 同一主线内的第 n 个追问为 n。
     * 用 sequence 区间推算, 无需新增列 —— 上一个 MAIN 之后到当前为止的 FOLLOW_UP 计数。
     */
    int followUpDepthAt(String sessionId, int sequence) {
        Integer depth = jdbc.queryForObject("""
                SELECT count(*) FROM interview_questions q
                WHERE q.session_id=? AND q.sequence <= ? AND q.kind='FOLLOW_UP'
                  AND q.sequence > COALESCE((SELECT max(m.sequence) FROM interview_questions m
                                             WHERE m.session_id=? AND m.sequence < ? AND m.kind='MAIN'), 0)
                """, Integer.class, sessionId, sequence, sessionId, sequence);
        return depth == null ? 0 : depth;
    }

    boolean hasExactlyOneUnansweredQuestion(String sessionId, int sequence) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM interview_questions
                WHERE session_id=? AND sequence=? AND answer IS NULL
                """, Integer.class, sessionId, sequence);
        return count != null && count == 1;
    }

    /** 反问环节已提交并被回答的提问数 (当前未作答的邀请题不计数)。 */
    int countCandidateAsks(String sessionId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM interview_questions
                WHERE session_id=? AND kind='CANDIDATE_ASK' AND answer IS NOT NULL
                """, Integer.class, sessionId);
        return count == null ? 0 : count;
    }

    /** 最后一道题的类型, 用于判断会话当前处于哪个阶段。 */
    String latestQuestionKind(String sessionId) {
        return jdbc.query("""
                SELECT kind FROM interview_questions WHERE session_id=? ORDER BY sequence DESC LIMIT 1
                """, rs -> rs.next() ? rs.getString(1) : null, sessionId);
    }

    /** 反问环节的问答记录 (question = 候选人的提问, reply = 面试官回答, tip = 提问质量点评)。 */
    List<InterviewSession.CandidateAsk> candidateAsks(String sessionId) {
        return jdbc.query("""
                SELECT answer, COALESCE(scorecard->>'reply', ''), COALESCE(scorecard->>'tip', '')
                FROM interview_questions
                WHERE session_id=? AND kind='CANDIDATE_ASK' AND answer IS NOT NULL
                  AND COALESCE(scorecard->>'reply', '') <> ''
                ORDER BY sequence
                """, (rs, i) -> new InterviewSession.CandidateAsk(rs.getString(1), rs.getString(2), rs.getString(3)), sessionId);
    }

    void advanceSession(String sessionId, int sequence, int mainQuestionCount, String status) {
        jdbc.update("""
                UPDATE interview_sessions
                SET current_question_sequence=?, main_question_count=?, status=?, version=version+1,
                    updated_at=now(), completed_at=CASE WHEN ?='COMPLETED' THEN now() ELSE completed_at END
                WHERE id=?
                """, sequence, mainQuestionCount, status, status, sessionId);
    }

    void recordAnswer(long questionId, String answer, Integer score, String scorecardJson, String requestId, String response) {
        jdbc.update("""
                UPDATE interview_questions
                SET answer=?, score=?, scorecard=?::jsonb, answer_request_id=?, response_message=?, answered_at=now()
                WHERE id=? AND answer IS NULL
                """, new Object[]{answer, score, scorecardJson, requestId, response, questionId},
                new int[]{java.sql.Types.VARCHAR, java.sql.Types.INTEGER, java.sql.Types.VARCHAR,
                        java.sql.Types.VARCHAR, java.sql.Types.VARCHAR, java.sql.Types.BIGINT});
    }

    InterviewSession.SessionRow findSession(long userId, String sessionId, boolean forUpdate) {
        return findSessionIfPresent(userId, sessionId, forUpdate)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "面试会话不存在"));
    }

    Optional<InterviewSession.SessionRow> findSessionIfPresent(long userId, String sessionId, boolean forUpdate) {
        String lockClause = forUpdate ? " FOR UPDATE OF s" : "";
        return jdbc.query("""
                SELECT s.id, s.target_role, s.topic, s.status, s.current_question_sequence, s.main_question_count,
                       COALESCE(array_to_string(s.skill_ids, ','), ''), COALESCE(b.job_description, ''),
                       COALESCE(b.interview_type, 'technical'), COALESCE(b.difficulty, 'MID'), COALESCE(b.duration_minutes, 45), s.deadline_at, s.retest_of
                FROM interview_sessions s LEFT JOIN interview_blueprints b ON b.id=s.blueprint_id
                WHERE s.id=? AND s.user_id=?
                """ + lockClause, (rs, i) -> mapSession(rs), sessionId, userId)
                .stream().findFirst();
    }

    InterviewSession.QuestionRow findCurrentQuestionForUpdate(String sessionId, int sequence) {
        List<InterviewSession.QuestionRow> questions = jdbc.query("""
                SELECT id, sequence, kind, prompt, answer, answer_request_id, response_message, skill_id,
                       assessment_contract::text
                FROM interview_questions WHERE session_id=? AND sequence=? FOR UPDATE
                """, (rs, i) -> new InterviewSession.QuestionRow(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9)), sessionId, sequence);
        if (questions.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "面试问题状态不完整");
        return questions.getFirst();
    }

    InterviewSession.QuestionRow findCurrentQuestion(String sessionId, int sequence) {
        List<InterviewSession.QuestionRow> questions = jdbc.query("""
                SELECT id, sequence, kind, prompt, answer, answer_request_id, response_message, skill_id,
                       assessment_contract::text
                FROM interview_questions WHERE session_id=? AND sequence=?
                """, (rs, i) -> new InterviewSession.QuestionRow(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9)), sessionId, sequence);
        if (questions.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "面试问题状态不完整");
        return questions.getFirst();
    }

    private InterviewSession.SessionRow mapSession(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<String> skills = rs.getString(7).isBlank() ? List.of() : Arrays.asList(rs.getString(7).split(","));
        return new InterviewSession.SessionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6),
                skills, rs.getString(8), rs.getString(9), rs.getString(10), rs.getInt(11), rs.getTimestamp(12).toInstant(), rs.getString(13));
    }

    private static String toTextArray(List<String> values) {
        return "{" + values.stream().map(value -> "\"" + value.replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",")) + "}";
    }
}
