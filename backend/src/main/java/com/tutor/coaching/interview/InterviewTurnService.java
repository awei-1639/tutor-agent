package com.tutor.coaching.interview;

import com.tutor.llm.LlmBudgetGuard;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Application API for submitting and retrying durable interview answer jobs. */
@Service
public class InterviewTurnService {
    private static final Logger log = LoggerFactory.getLogger(InterviewTurnService.class);

    private final InterviewTurnJobStore jobs;
    private final InterviewSessionRepository sessions;
    private final MeterRegistry metrics;
    private volatile LlmBudgetGuard budgetGuard;

    @Autowired
    public InterviewTurnService(InterviewTurnJobStore jobs, InterviewSessionRepository sessions,
                                MeterRegistry metrics) {
        this.jobs = jobs;
        this.sessions = sessions;
        this.metrics = metrics;
    }

    /** Compatibility constructor retained for database-focused integration tests. */
    public InterviewTurnService(org.springframework.jdbc.core.JdbcTemplate jdbc,
                                InterviewSession interviews, MeterRegistry metrics) {
        this(new InterviewTurnJobStore(jdbc), new InterviewSessionRepository(jdbc), metrics);
    }

    public record TurnJob(String id, String sessionId, String requestId, String status, int attempts,
                          String responseStatus, String responseMessage, String lastError,
                          Instant createdAt, Instant finishedAt) {}

    /** 可选注入：回合 trace 归属到用户，使面试评分消耗计入用户日配额。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setBudgetGuard(LlmBudgetGuard budgetGuard) {
        this.budgetGuard = budgetGuard;
    }

    @Transactional
    public TurnJob submit(long userId, String sessionId, String answer, String requestId, String traceId) {
        var existing = jobs.findByRequest(userId, sessionId, requestId);
        if (existing.isPresent()) return existing.get();
        attributeTrace(traceId, userId);

        InterviewSession.SessionRow session = sessions.findSession(userId, sessionId, true);
        if (!"IN_PROGRESS".equals(session.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "面试已结束，不能继续提交回答");
        }
        if (!Instant.now().isBefore(session.deadlineAt())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "面试时间已到，请结束面试查看复盘");
        }
        if (!sessions.hasExactlyOneUnansweredQuestion(sessionId, session.currentSequence())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前问题已经提交过回答");
        }

        String id = UUID.randomUUID().toString();
        int inserted = jobs.insert(id, userId, sessionId, session.currentSequence(), requestId, answer, traceId);
        if (inserted == 1) {
            event("submitted");
            return get(userId, sessionId, id);
        }
        return jobs.findByRequest(userId, sessionId, requestId)
                .orElseThrow(() -> new IllegalStateException("回答任务创建后不可见"));
    }

    public TurnJob get(long userId, String sessionId, String jobId) {
        return jobs.find(userId, sessionId, jobId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "回答任务不存在"));
    }

    /** Explicit user retry after the bounded automatic attempts are exhausted. */
    @Transactional
    public TurnJob retry(long userId, String sessionId, String jobId) {
        TurnJob current = get(userId, sessionId, jobId);
        if (!"FAILED".equals(current.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该回答任务当前不可重新评分");
        }
        if (jobs.resetForRetry(jobId, userId, sessionId) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该回答任务状态已变化，请刷新后重试");
        }
        event("user_retried");
        return get(userId, sessionId, jobId);
    }

    private void attributeTrace(String traceId, long userId) {
        if (budgetGuard == null) return;
        try {
            budgetGuard.attributeTrace(traceId, userId);
        } catch (RuntimeException error) {
            log.warn("budget attribution failed trace={} type={}", traceId, error.getClass().getSimpleName());
        }
    }

    private void event(String result) {
        Counter.builder("tutor.interview.turn_jobs.events").tag("result", result).register(metrics).increment();
    }
}
