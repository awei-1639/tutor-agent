package com.tutor.coaching.interview;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for the post-interview completion workflow.
 *
 * <p>The worker owns execution policy; this class owns the durable job state,
 * lease fencing, and the evidence writes. Keeping those SQL statements here
 * makes it possible to reason about retries without reading the report
 * rendering code.</p>
 */
@Repository
class InterviewCompletionJobStore {
    private final JdbcTemplate jdbc;

    record Job(long id, long userId, String sessionId, UUID leaseToken) {}

    record Status(String sessionId, String status, int attempts, String lastError,
                  String evidenceStatus, String learningPlanStatus,
                  Instant createdAt, Instant startedAt, Instant finishedAt) {}

    InterviewCompletionJobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void enqueue(long userId, String sessionId) {
        jdbc.update("""
                INSERT INTO interview_completion_jobs (user_id, session_id)
                VALUES (?, ?)
                ON CONFLICT (session_id) DO NOTHING
                """, userId, sessionId);
    }

    Optional<Status> status(long userId, String sessionId) {
        return jdbc.query("""
                SELECT session_id, status, attempts, last_error, evidence_status, learning_plan_status,
                       created_at, started_at, finished_at
                FROM interview_completion_jobs WHERE user_id=? AND session_id=?
                """, (rs, i) -> new Status(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                rs.getString(5), rs.getString(6), instant(rs, 7), nullableInstant(rs, 8), nullableInstant(rs, 9)),
                userId, sessionId).stream().findFirst();
    }

    Optional<Job> claimNext() {
        return jdbc.query("""
                WITH next_job AS (
                    SELECT id FROM interview_completion_jobs
                    WHERE (status='queued' AND attempts < 3)
                       OR (status='running' AND (lease_until IS NULL OR lease_until < now()) AND attempts < 3)
                    ORDER BY id
                    FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE interview_completion_jobs j
                SET status='running', attempts=attempts+1, started_at=now(), last_error=NULL,
                    lease_token=?, lease_until=now() + interval '10 minutes'
                FROM next_job
                WHERE j.id=next_job.id
                RETURNING j.id, j.user_id, j.session_id, j.lease_token
                """, (rs, i) -> new Job(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getObject(4, UUID.class)), UUID.randomUUID()).stream().findFirst();
    }

    boolean ownsLease(Job job) {
        Integer active = jdbc.queryForObject("""
                SELECT count(*) FROM interview_completion_jobs
                WHERE id=? AND status='running' AND lease_token=? AND lease_until > now()
                """, Integer.class, job.id(), job.leaseToken());
        return active != null && active == 1;
    }

    boolean markEvidenceCompleted(Job job) {
        return jdbc.update("""
                UPDATE interview_completion_jobs SET evidence_status='completed'
                WHERE id=? AND status='running' AND lease_token=? AND lease_until > now()
                """, job.id(), job.leaseToken()) == 1;
    }

    boolean markCompleted(Job job) {
        return jdbc.update("""
                UPDATE interview_completion_jobs
                SET status='completed', evidence_status='completed', learning_plan_status='completed', finished_at=now(),
                    last_error=NULL, lease_token=NULL, lease_until=NULL
                WHERE id=? AND status='running' AND lease_token=? AND lease_until > now()
                """, job.id(), job.leaseToken()) == 1;
    }

    void markFailure(Job job, Exception error) {
        String message = error.getMessage() == null || error.getMessage().isBlank()
                ? "面试闭环任务失败" : error.getMessage();
        if (message.length() > 500) message = message.substring(0, 500);
        jdbc.update("""
                UPDATE interview_completion_jobs
                SET status=CASE WHEN attempts >= 3 THEN 'failed' ELSE 'queued' END,
                    learning_plan_status=CASE WHEN attempts >= 3 AND evidence_status='completed'
                        THEN 'failed' ELSE learning_plan_status END,
                    last_error=?, finished_at=CASE WHEN attempts >= 3 THEN now() ELSE NULL END,
                    lease_token=NULL, lease_until=NULL
                WHERE id=? AND status='running' AND lease_token=? AND lease_until > now()
                """, message, job.id(), job.leaseToken());
    }

    InterviewSession.SessionRow session(long userId, String sessionId) {
        return findSession(userId, sessionId)
                .orElseThrow(() -> new IllegalStateException("interview session missing: " + sessionId));
    }

    Optional<InterviewSession.SessionRow> findSession(long userId, String sessionId) {
        return jdbc.query("""
                SELECT s.id, s.target_role, s.topic, s.status, s.current_question_sequence, s.main_question_count,
                       COALESCE(array_to_string(s.skill_ids, ','), ''), COALESCE(b.job_description, ''),
                       COALESCE(b.interview_type, 'technical'), COALESCE(b.difficulty, 'MID'),
                       COALESCE(b.duration_minutes, 45), s.deadline_at, s.retest_of
                FROM interview_sessions s LEFT JOIN interview_blueprints b ON b.id=s.blueprint_id
                WHERE s.id=? AND s.user_id=?
                """, (rs, i) -> mapSession(rs), sessionId, userId).stream().findFirst();
    }

    List<String> weakSkills(String sessionId) {
        return jdbc.query("""
                SELECT DISTINCT COALESCE(skill_id, '') FROM interview_questions
                WHERE session_id=? AND score IS NOT NULL AND score < 7 AND skill_id IS NOT NULL
                """, (rs, i) -> rs.getString(1), sessionId);
    }

    List<InterviewSession.QuestionScore> scores(String sessionId, String skillId) {
        return jdbc.query("""
                SELECT prompt, score, COALESCE(scorecard::text, '{}') FROM interview_questions
                WHERE session_id=? AND skill_id=? AND score IS NOT NULL
                """, (rs, i) -> new InterviewSession.QuestionScore(rs.getString(1), rs.getInt(2), rs.getString(3)),
                sessionId, skillId);
    }

    void saveEvidence(long userId, String sessionId, String skillId, double average,
                      double confidence, String evidence) {
        jdbc.update("""
                INSERT INTO interview_skill_evidence (user_id, session_id, skill_id, average_score, confidence, evidence)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (session_id, skill_id) DO UPDATE SET average_score=EXCLUDED.average_score,
                  confidence=EXCLUDED.confidence, evidence=EXCLUDED.evidence
                """, userId, sessionId, skillId, average, confidence, evidence);
    }

    private InterviewSession.SessionRow mapSession(ResultSet rs) throws SQLException {
        List<String> skills = rs.getString(7).isBlank() ? List.of() : Arrays.asList(rs.getString(7).split(","));
        return new InterviewSession.SessionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getInt(5), rs.getInt(6), skills, rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getInt(11), rs.getTimestamp(12).toInstant(), rs.getString(13));
    }

    private static Instant instant(ResultSet rs, int index) throws SQLException {
        return rs.getTimestamp(index).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, int index) throws SQLException {
        return rs.getTimestamp(index) == null ? null : rs.getTimestamp(index).toInstant();
    }
}
