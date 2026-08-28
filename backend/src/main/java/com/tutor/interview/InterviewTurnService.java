package com.tutor.interview;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** Durable worker for answer scoring. Database locks are only used while claiming or committing state. */
@Service
public class InterviewTurnService {
    private static final Logger log = LoggerFactory.getLogger(InterviewTurnService.class);
    private static final int MAX_ATTEMPTS = 3;
    private final JdbcTemplate jdbc;
    private final InterviewSession interviews;
    private final Semaphore slots = new Semaphore(2);
    private final MeterRegistry metrics;

    InterviewTurnService(JdbcTemplate jdbc, InterviewSession interviews, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.interviews = interviews;
        this.metrics = metrics;
        Gauge.builder("tutor.interview.turn_jobs.pending", jdbc,
                        source -> source.queryForObject("SELECT count(*) FROM interview_turn_jobs WHERE status IN ('PENDING','RETRYABLE_FAILED')", Integer.class))
                .description("Interview answer jobs awaiting an LLM worker").register(metrics);
    }

    public record TurnJob(String id, String sessionId, String requestId, String status, int attempts,
                          String responseStatus, String responseMessage, String lastError, Instant createdAt, Instant finishedAt) {}
    private record ClaimedJob(String id, long userId, String sessionId, String answer, String requestId,
                              String traceId, int attempts, UUID leaseToken) {}

    @Transactional
    public TurnJob submit(long userId, String sessionId, String answer, String requestId, String traceId) {
        List<TurnJob> existing = jdbc.query("""
                SELECT id, session_id, request_id, status, attempts, response_status, response_message, last_error, created_at, finished_at
                FROM interview_turn_jobs WHERE user_id=? AND session_id=? AND request_id=?
                """, (rs, i) -> map(rs), userId, sessionId, requestId);
        if (!existing.isEmpty()) return existing.getFirst();

        InterviewSession.SessionRow session = jdbc.query("""
                SELECT s.id, s.target_role, s.topic, s.status, s.current_question_sequence, s.main_question_count,
                       COALESCE(array_to_string(s.skill_ids, ','), ''), COALESCE(b.job_description, ''),
                       COALESCE(b.interview_type, 'technical'), COALESCE(b.difficulty, 'MID'), COALESCE(b.duration_minutes, 45), s.deadline_at, s.retest_of
                FROM interview_sessions s LEFT JOIN interview_blueprints b ON b.id=s.blueprint_id
                WHERE s.id=? AND s.user_id=? FOR UPDATE OF s
                """, (rs, i) -> new InterviewSession.SessionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getInt(5), rs.getInt(6), List.of(), rs.getString(8), rs.getString(9), rs.getString(10), rs.getInt(11),
                rs.getTimestamp(12).toInstant(), rs.getString(13)), sessionId, userId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "面试会话不存在"));
        if (!"IN_PROGRESS".equals(session.status())) throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "面试已结束，不能继续提交回答");
        if (!Instant.now().isBefore(session.deadlineAt())) throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "面试时间已到，请结束面试查看复盘");
        Integer unanswered = jdbc.queryForObject("SELECT count(*) FROM interview_questions WHERE session_id=? AND sequence=? AND answer IS NULL",
                Integer.class, sessionId, session.currentSequence());
        if (unanswered == null || unanswered != 1) throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "当前问题已经提交过回答");

        String id = UUID.randomUUID().toString();
        int inserted = jdbc.update("""
                INSERT INTO interview_turn_jobs (id, user_id, session_id, question_sequence, request_id, answer, trace_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (session_id, request_id) DO NOTHING
                """, id, userId, sessionId, session.currentSequence(), requestId, answer, traceId);
        if (inserted == 1) {
            event("submitted");
            return get(userId, sessionId, id);
        }
        // A concurrent tab won the unique key race. Return its durable job rather than failing the retry.
        return jdbc.query("""
                SELECT id, session_id, request_id, status, attempts, response_status, response_message, last_error, created_at, finished_at
                FROM interview_turn_jobs WHERE user_id=? AND session_id=? AND request_id=?
                """, (rs, i) -> map(rs), userId, sessionId, requestId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("回答任务创建后不可见"));
    }

    public TurnJob get(long userId, String sessionId, String jobId) {
        return jdbc.query("""
                SELECT id, session_id, request_id, status, attempts, response_status, response_message, last_error, created_at, finished_at
                FROM interview_turn_jobs WHERE id=? AND user_id=? AND session_id=?
                """, (rs, i) -> map(rs), jobId, userId, sessionId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "回答任务不存在"));
    }

    /** Explicit user retry after the bounded automatic attempts are exhausted. */
    @Transactional
    public TurnJob retry(long userId, String sessionId, String jobId) {
        TurnJob current = get(userId, sessionId, jobId);
        if (!"FAILED".equals(current.status())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "该回答任务当前不可重新评分");
        }
        int updated = jdbc.update("""
                UPDATE interview_turn_jobs
                SET status='PENDING', attempts=0, lease_until=NULL, lease_token=NULL, next_attempt_at=now(),
                    response_status=NULL, response_message=NULL, last_error=NULL, finished_at=NULL, updated_at=now()
                WHERE id=? AND user_id=? AND session_id=? AND status='FAILED'
                """, jobId, userId, sessionId);
        if (updated != 1) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "该回答任务状态已变化，请刷新后重试");
        }
        event("user_retried");
        return get(userId, sessionId, jobId);
    }

    @Scheduled(fixedDelayString = "${tutor.interview.turn.poll-ms:500}")
    void dispatch() {
        if (!slots.tryAcquire()) return;
        ClaimedJob job = claim();
        if (job == null) { slots.release(); return; }
        Thread.startVirtualThread(() -> {
            try { process(job); } finally { slots.release(); }
        });
    }

    private ClaimedJob claim() {
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
                """, (rs, i) -> new ClaimedJob(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getInt(7), rs.getObject(8, UUID.class)),
                UUID.randomUUID()).stream().findFirst().orElse(null);
    }

    private void process(ClaimedJob job) {
        try {
            InterviewSession.TurnEvaluation evaluation = interviews.evaluateTurn(job.userId(), job.sessionId(), job.answer(), job.traceId());
            if (!ownsLease(job)) {
                log.info("面试回答任务租约已失效，跳过旧 worker job={}", job.id());
                return;
            }
            InterviewSession.InterviewMessage result = interviews.commitTurn(job.userId(), job.sessionId(), job.answer(), job.requestId(), evaluation);
            jdbc.update("""
                    UPDATE interview_turn_jobs SET status='COMPLETED', response_status=?, response_message=?, last_error=NULL,
                      lease_until=NULL, lease_token=NULL, finished_at=now(), updated_at=now()
                    WHERE id=? AND status='PROCESSING' AND lease_token=? AND lease_until > now()
                    """, result.status(), result.message(), job.id(), job.leaseToken());
            event("completed");
        } catch (Exception error) {
            boolean retryable = job.attempts() < MAX_ATTEMPTS && !(error instanceof ResponseStatusException);
            String status = retryable ? "RETRYABLE_FAILED" : "FAILED";
            jdbc.update("""
                    UPDATE interview_turn_jobs SET status=?, last_error=?, lease_until=NULL, lease_token=NULL,
                      next_attempt_at=CASE WHEN ? THEN now() + interval '5 seconds' ELSE next_attempt_at END,
                      finished_at=CASE WHEN ? THEN NULL ELSE now() END, updated_at=now()
                    WHERE id=? AND status='PROCESSING' AND lease_token=? AND lease_until > now()
                    """, status, safeError(error), retryable, retryable, job.id(), job.leaseToken());
            log.warn("interview turn failed job={} attempt={} retryable={} type={}", job.id(), job.attempts(), retryable,
                    error.getClass().getSimpleName());
            event(retryable ? "retryable_failed" : "failed");
        }
    }

    private boolean ownsLease(ClaimedJob job) {
        Integer active = jdbc.queryForObject("""
                SELECT count(*) FROM interview_turn_jobs
                WHERE id=? AND status='PROCESSING' AND lease_token=? AND lease_until > now()
                """, Integer.class, job.id(), job.leaseToken());
        return active != null && active == 1;
    }

    private TurnJob map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TurnJob(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getTimestamp(9).toInstant(),
                rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant());
    }

    private String safeError(Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private void event(String result) {
        Counter.builder("tutor.interview.turn_jobs.events").tag("result", result).register(metrics).increment();
    }
}
