package com.tutor.interview;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL boundary for durable interview-answer jobs and their fencing tokens. */
@Repository
final class InterviewTurnJobStore {
    private final JdbcTemplate jdbc;

    record ClaimedJob(String id, long userId, String sessionId, String answer, String requestId,
                      String traceId, int attempts, UUID leaseToken) {}

    InterviewTurnJobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<InterviewTurnService.TurnJob> findByRequest(long userId, String sessionId, String requestId) {
        return jdbc.query("""
                SELECT id, session_id, request_id, status, attempts, response_status, response_message,
                       last_error, created_at, finished_at
                FROM interview_turn_jobs WHERE user_id=? AND session_id=? AND request_id=?
                """, (rs, i) -> mapTurnJob(rs), userId, sessionId, requestId).stream().findFirst();
    }

    Optional<InterviewTurnService.TurnJob> find(long userId, String sessionId, String jobId) {
        return jdbc.query("""
                SELECT id, session_id, request_id, status, attempts, response_status, response_message,
                       last_error, created_at, finished_at
                FROM interview_turn_jobs WHERE id=? AND user_id=? AND session_id=?
                """, (rs, i) -> mapTurnJob(rs), jobId, userId, sessionId).stream().findFirst();
    }

    int insert(String id, long userId, String sessionId, int sequence, String requestId,
               String answer, String traceId) {
        return jdbc.update("""
                INSERT INTO interview_turn_jobs (id, user_id, session_id, question_sequence, request_id, answer, trace_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (session_id, request_id) DO NOTHING
                """, id, userId, sessionId, sequence, requestId, answer, traceId);
    }

    int resetForRetry(String jobId, long userId, String sessionId) {
        return jdbc.update("""
                UPDATE interview_turn_jobs
                SET status='PENDING', attempts=0, lease_until=NULL, lease_token=NULL, next_attempt_at=now(),
                    response_status=NULL, response_message=NULL, last_error=NULL, finished_at=NULL, updated_at=now()
                WHERE id=? AND user_id=? AND session_id=? AND status='FAILED'
                """, jobId, userId, sessionId);
    }

    Optional<ClaimedJob> claimNext() {
        return jdbc.query("""
                WITH candidate AS (
                  SELECT id FROM interview_turn_jobs
                  WHERE (status IN ('PENDING','RETRYABLE_FAILED') AND next_attempt_at <= now())
                     OR (status='PROCESSING' AND lease_until < now())
                  ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE interview_turn_jobs j SET status='PROCESSING', attempts=j.attempts+1,
                    started_at=COALESCE(j.started_at, now()), lease_token=?,
                    lease_until=now() + interval '90 seconds', updated_at=now()
                FROM candidate WHERE j.id=candidate.id
                RETURNING j.id, j.user_id, j.session_id, j.answer, j.request_id, j.trace_id, j.attempts, j.lease_token
                """, (rs, i) -> new ClaimedJob(rs.getString(1), rs.getLong(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7), rs.getObject(8, UUID.class)),
                UUID.randomUUID()).stream().findFirst();
    }

    boolean ownsLease(ClaimedJob job) {
        Integer active = jdbc.queryForObject("""
                SELECT count(*) FROM interview_turn_jobs
                WHERE id=? AND status='PROCESSING' AND lease_token=? AND lease_until > now()
                """, Integer.class, job.id(), job.leaseToken());
        return active != null && active == 1;
    }

    boolean complete(ClaimedJob job, String status, String message) {
        return jdbc.update("""
                UPDATE interview_turn_jobs SET status='COMPLETED', response_status=?, response_message=?, last_error=NULL,
                  lease_until=NULL, lease_token=NULL, finished_at=now(), updated_at=now()
                WHERE id=? AND status='PROCESSING' AND lease_token=? AND lease_until > now()
                """, status, message, job.id(), job.leaseToken()) == 1;
    }

    boolean fail(ClaimedJob job, String status, String error, boolean retryable) {
        return jdbc.update("""
                UPDATE interview_turn_jobs SET status=?, last_error=?, lease_until=NULL, lease_token=NULL,
                  next_attempt_at=CASE WHEN ? THEN now() + interval '5 seconds' ELSE next_attempt_at END,
                  finished_at=CASE WHEN ? THEN NULL ELSE now() END, updated_at=now()
                WHERE id=? AND status='PROCESSING' AND lease_token=? AND lease_until > now()
                """, status, error, retryable, retryable, job.id(), job.leaseToken()) == 1;
    }

    private InterviewTurnService.TurnJob mapTurnJob(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InterviewTurnService.TurnJob(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getInt(5), rs.getString(6), rs.getString(7), rs.getString(8),
                instant(rs, 9), nullableInstant(rs, 10));
    }

    private static Instant instant(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        return rs.getTimestamp(index).toInstant();
    }

    private static Instant nullableInstant(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        return rs.getTimestamp(index) == null ? null : rs.getTimestamp(index).toInstant();
    }
}
