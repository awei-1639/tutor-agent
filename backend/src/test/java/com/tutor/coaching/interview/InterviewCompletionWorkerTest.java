package com.tutor.coaching.interview;

import com.tutor.coaching.plan.PlanService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class InterviewCompletionWorkerTest {
    private final InterviewCompletionJobStore jobs = mock(InterviewCompletionJobStore.class);
    private final InterviewLlmService interviewer = mock(InterviewLlmService.class);
    private final PlanService plans = mock(PlanService.class);
    private final ExecutorService executor = mock(ExecutorService.class);
    private final InterviewCompletionWorker worker = new InterviewCompletionWorker(
            jobs, interviewer, plans, executor, new Semaphore(2));
    private final InterviewCompletionJobStore.Job job = new InterviewCompletionJobStore.Job(
            7L, 42L, "session-7", UUID.randomUUID());
    private final InterviewSession.SessionRow session = new InterviewSession.SessionRow(
            "session-7", "后端开发", "缓存", "COMPLETED", 2, 2, List.of("skill:缓存"),
            "", "technical", "MID", 45, Instant.now(), null);

    @Test
    void skipsExpiredLeaseBeforeWritingEvidenceOrCreatingPlan() {
        when(jobs.session(job.userId(), job.sessionId())).thenReturn(session);
        when(jobs.ownsLease(job)).thenReturn(false);

        worker.process(job);

        verify(jobs, never()).weakSkills(job.sessionId());
        verify(jobs, never()).markEvidenceCompleted(job);
        verify(plans, never()).createEvidenceTasks(job.userId(), "后端开发", List.of());
    }

    @Test
    void createsEvidenceBeforeTheLearningPlanAndOnlyForWeakSkills() {
        when(jobs.session(job.userId(), job.sessionId())).thenReturn(session);
        when(jobs.ownsLease(job)).thenReturn(true);
        when(jobs.weakSkills(job.sessionId())).thenReturn(List.of("skill:缓存"));
        when(jobs.scores(job.sessionId(), "skill:缓存")).thenReturn(List.of(
                new InterviewSession.QuestionScore("如何处理缓存击穿", 5,
                        "{\"confidence\":0.8,\"missing_points\":[\"监控\"]}")));
        when(interviewer.scorecard("{\"confidence\":0.8,\"missing_points\":[\"监控\"]}"))
                .thenReturn(new InterviewSession.Scorecard(5, List.of(), List.of("监控"), 0.8, "test"));
        when(jobs.markEvidenceCompleted(job)).thenReturn(true);

        worker.process(job);

        verify(jobs).saveEvidence(eq(job.userId()), eq(job.sessionId()), eq("skill:缓存"), eq(5D), eq(0.8D), anyString());
        verify(plans).createEvidenceTasks(job.userId(), "后端开发", List.of("skill:缓存"));
        verify(jobs).markCompleted(job);
    }
}
