package com.tutor.coaching.interview;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL boundary for durable interview-answer jobs and their fencing tokens. */
@Repository
class InterviewTurnJobStore {
    private static final long LEASE_SECONDS = 90;
    private static final int MAX_ATTEMPTS = 3;
    private static final com.tutor.platform.jobs.LeasedJobQueue.LeaseTable TABLE =
            com.tutor.platform.jobs.LeasedJobQueue.LeaseTable.of(
                    "interview_turn_jobs", "PROCESSING", "COMPLETED", "id=?");

    private final JdbcTemplate jdbc;
    private final com.tutor.platform.jobs.LeasedJobQueue queue;

    record ClaimedJob(String id, long userId, String sessionId, String answer, String requestId,
                      String traceId, int attempts, UUID leaseToken) {}

    InterviewTurnJobStore(JdbcTemplate jdbc, com.tutor.platform.jobs.LeasedJobQueue queue) {
        this.jdbc = jdbc;
        this.queue = queue;
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
        return queue.claimNext(TABLE,
                "(status IN ('PENDING','RETRYABLE_FAILED') AND next_attempt_at <= now())"
                        + " OR (status='PROCESSING' AND lease_until < now() AND attempts < " + MAX_ATTEMPTS + ")",
                "status='PROCESSING', attempts=j.attempts+1, started_at=COALESCE(j.started_at, now()),"
                        + " lease_token=?, lease_until=now() + (? * interval '1 second'), updated_at=now()",
                "created_at",
                "j.id, j.user_id, j.session_id, j.answer, j.request_id, j.trace_id, j.attempts, j.lease_token",
                (rs, i) -> new ClaimedJob(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7),
                        rs.getObject(8, UUID.class)),
                UUID.randomUUID(), LEASE_SECONDS);
    }

    /** 把崩溃 worker 遗留、租约耗尽且超过重试上限的任务批量置为 FAILED（死信）。 */
    int sweepAbandoned() {
        return queue.expireExhausted(TABLE,
                "status='FAILED', last_error=?, finished_at=now(), updated_at=now()",
                new Object[]{"任务租约已耗尽"}, "attempts >= ?", MAX_ATTEMPTS);
    }

    /** 续租: LLM 评分可能逼近 90s 租约, 不续租会被接管后重复执行。 */
    void renew(ClaimedJob job) {
        queue.renew(TABLE, job.id(), job.leaseToken(), LEASE_SECONDS);
    }

    boolean ownsLease(ClaimedJob job) {
        return queue.owns(TABLE, job.id(), job.leaseToken());
    }

    boolean complete(ClaimedJob job, String status, String message) {
        return queue.complete(TABLE, job.id(), job.leaseToken(),
                ", response_status=?, response_message=?, last_error=NULL, finished_at=now(), updated_at=now()",
                status, message);
    }

    boolean fail(ClaimedJob job, String status, String error, boolean retryable) {
        return queue.fencedUpdate(TABLE, job.id(), job.leaseToken(), """
                status=?, last_error=?, lease_token=NULL, lease_until=NULL,
                  next_attempt_at=CASE WHEN ? THEN now() + interval '5 seconds' ELSE next_attempt_at END,
                  finished_at=CASE WHEN ? THEN NULL ELSE now() END, updated_at=now()""",
                status, error, retryable, retryable);
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
