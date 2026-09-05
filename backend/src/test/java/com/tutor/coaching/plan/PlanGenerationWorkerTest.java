package com.tutor.coaching.plan;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanGenerationWorkerTest {
    private final PlanStore store = mock(PlanStore.class);
    private final PlanService plans = mock(PlanService.class);
    private final PlanGenerationWorker worker = new PlanGenerationWorker(
            store, plans, mock(ExecutorService.class), new Semaphore(2));
    private final PlanStore.QueuedJob job = new PlanStore.QueuedJob(
            7L, 42L, "转后端岗位", "Java", "", null, UUID.randomUUID());

    @Test
    void skipsAJobWhenTheLeaseIsNoLongerOwned() {
        when(store.ownsLease(job)).thenReturn(false);

        worker.process(job);

        verify(plans, never()).generateWeeklyPlan(
                job.userId(), job.goal(), job.currentSkills(), job.checkinHistory(), "plan-job-7");
        verify(store, never()).completeGeneration(job, 1L);
        verify(store, never()).failGeneration(job, "LLM 返回无效计划或调用失败");
    }

    @Test
    void completesTheClaimedJobWithTheGeneratedPlan() {
        when(store.ownsLease(job)).thenReturn(true);
        when(plans.generateWeeklyPlan(
                job.userId(), job.goal(), job.currentSkills(), job.checkinHistory(), "plan-job-7"))
                .thenReturn(new PlanModels.Plan(11L, 42L, "后端计划",
                        LocalDate.now(), LocalDate.now().plusDays(6), "active"));

        worker.process(job);

        verify(store).completeGeneration(job, 11L);
        verify(store, never()).failGeneration(job, "LLM 返回无效计划或调用失败");
    }

    @Test
    void marksTheClaimedJobFailedWhenGenerationReturnsNoPlan() {
        when(store.ownsLease(job)).thenReturn(true);
        when(plans.generateWeeklyPlan(
                job.userId(), job.goal(), job.currentSkills(), job.checkinHistory(), "plan-job-7"))
                .thenReturn(null);

        worker.process(job);

        verify(store).failGeneration(job, "LLM 返回无效计划或调用失败");
        verify(store, never()).completeGeneration(job, 1L);
    }
}
