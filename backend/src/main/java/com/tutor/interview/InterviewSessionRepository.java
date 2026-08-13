package com.tutor.interview;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

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

    void advanceSession(String sessionId, int sequence, int mainQuestionCount, String status) {
        jdbc.update("""
                UPDATE interview_sessions
                SET current_question_sequence=?, main_question_count=?, status=?, version=version+1,
                    updated_at=now(), completed_at=CASE WHEN ?='COMPLETED' THEN now() ELSE completed_at END
                WHERE id=?
                """, sequence, mainQuestionCount, status, status, sessionId);
    }

    void recordAnswer(long questionId, String answer, int score, String scorecardJson, String requestId, String response) {
        jdbc.update("""
                UPDATE interview_questions
                SET answer=?, score=?, scorecard=?::jsonb, answer_request_id=?, response_message=?, answered_at=now()
                WHERE id=? AND answer IS NULL
                """, answer, score, scorecardJson, requestId, response, questionId);
    }

    InterviewSession.SessionRow findSession(long userId, String sessionId, boolean forUpdate) {
        String lockClause = forUpdate ? " FOR UPDATE OF s" : "";
        List<InterviewSession.SessionRow> sessions = jdbc.query("""
                SELECT s.id, s.target_role, s.topic, s.status, s.current_question_sequence, s.main_question_count,
                       COALESCE(array_to_string(s.skill_ids, ','), ''), COALESCE(b.job_description, ''),
                       COALESCE(b.interview_type, 'technical'), COALESCE(b.difficulty, 'MID'), COALESCE(b.duration_minutes, 45), s.deadline_at, s.retest_of
                FROM interview_sessions s LEFT JOIN interview_blueprints b ON b.id=s.blueprint_id
                WHERE s.id=? AND s.user_id=?
                """ + lockClause, (rs, i) -> mapSession(rs), sessionId, userId);
        if (sessions.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "面试会话不存在");
        return sessions.getFirst();
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
}
