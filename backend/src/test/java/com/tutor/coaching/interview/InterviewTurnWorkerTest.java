package com.tutor.coaching.interview;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.Semaphore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewTurnWorkerTest {
    private final InterviewTurnJobStore jobs = mock(InterviewTurnJobStore.class);
    private final InterviewSession interviews = mock(InterviewSession.class);
    private final InterviewTurnWorker worker = new InterviewTurnWorker(
            jobs, interviews, new SimpleMeterRegistry(), new Semaphore(2));
    private final InterviewTurnJobStore.ClaimedJob job = new InterviewTurnJobStore.ClaimedJob(
            "job-1", 7L, "session-1", "candidate answer", "request-1", "trace-1", 1, UUID.randomUUID());

    @Test
    void doesNotCommitWhenTheLeaseExpiresAfterScoring() {
        when(interviews.evaluateTurn(job.userId(), job.sessionId(), job.answer(), job.traceId())).thenReturn(null);
        when(jobs.ownsLease(job)).thenReturn(false);

        worker.process(job);

        verify(interviews, never()).commitTurn(anyLong(), anyString(), anyString(), anyString(), any());
        verify(jobs, never()).complete(any(), any(), any());
    }

    @Test
    void commitsAClaimedTurnAndRecordsCompletion() {
        InterviewSession.InterviewMessage result =
                new InterviewSession.InterviewMessage(job.sessionId(), "IN_PROGRESS", "下一题");
        when(interviews.evaluateTurn(job.userId(), job.sessionId(), job.answer(), job.traceId())).thenReturn(null);
        when(jobs.ownsLease(job)).thenReturn(true);
        when(interviews.commitTurn(job.userId(), job.sessionId(), job.answer(), job.requestId(), null))
                .thenReturn(result);

        worker.process(job);

        verify(jobs).complete(job, "IN_PROGRESS", "下一题");
        verify(jobs, never()).fail(any(), any(), any(), eq(true));
    }

    @Test
    void retriesUnexpectedScoringFailureUntilTheAttemptLimit() {
        when(interviews.evaluateTurn(job.userId(), job.sessionId(), job.answer(), job.traceId()))
                .thenThrow(new IllegalStateException("provider unavailable"));

        worker.process(job);

        verify(jobs).fail(job, "RETRYABLE_FAILED", "provider unavailable", true);
        verify(jobs, never()).complete(any(), any(), any());
    }
}
