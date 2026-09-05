package com.tutor.coaching.interview;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.Semaphore;

/** Executes claimed answer-scoring jobs without owning their HTTP submission API. */
@Component
final class InterviewTurnWorker {
    private static final Logger log = LoggerFactory.getLogger(InterviewTurnWorker.class);
    private static final int MAX_ATTEMPTS = 3;

    private final InterviewTurnJobStore jobs;
    private final InterviewSession interviews;
    private final MeterRegistry metrics;
    private final Semaphore slots;

    @Autowired
    InterviewTurnWorker(InterviewTurnJobStore jobs, InterviewSession interviews, MeterRegistry metrics) {
        this(jobs, interviews, metrics, new Semaphore(2));
    }

    InterviewTurnWorker(InterviewTurnJobStore jobs, InterviewSession interviews, MeterRegistry metrics,
                        Semaphore slots) {
        this.jobs = jobs;
        this.interviews = interviews;
        this.metrics = metrics;
        this.slots = slots;
    }

    @Scheduled(fixedDelayString = "${tutor.interview.turn.poll-ms:500}")
    void dispatch() {
        if (!slots.tryAcquire()) return;
        var job = jobs.claimNext();
        if (job.isEmpty()) {
            slots.release();
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                process(job.get());
            } finally {
                slots.release();
            }
        });
    }

    void process(InterviewTurnJobStore.ClaimedJob job) {
        try {
            InterviewSession.TurnEvaluation evaluation = interviews.evaluateTurn(
                    job.userId(), job.sessionId(), job.answer(), job.traceId());
            if (!jobs.ownsLease(job)) {
                log.info("面试回答任务租约已失效，跳过旧 worker job={}", job.id());
                return;
            }
            InterviewSession.InterviewMessage result = interviews.commitTurn(
                    job.userId(), job.sessionId(), job.answer(), job.requestId(), evaluation);
            jobs.complete(job, result.status(), result.message());
            event("completed");
        } catch (Exception error) {
            boolean retryable = job.attempts() < MAX_ATTEMPTS && !(error instanceof ResponseStatusException);
            jobs.fail(job, retryable ? "RETRYABLE_FAILED" : "FAILED", safeError(error), retryable);
            log.warn("interview turn failed job={} attempt={} retryable={} type={}",
                    job.id(), job.attempts(), retryable, error.getClass().getSimpleName());
            event(retryable ? "retryable_failed" : "failed");
        }
    }

    private String safeError(Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private void event(String result) {
        Counter.builder("tutor.interview.turn_jobs.events").tag("result", result).register(metrics).increment();
    }
}
