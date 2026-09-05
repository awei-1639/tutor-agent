package com.tutor.coaching.plan;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** Durable worker for queued plan generation; persistence and plan creation remain separate. */
@Component
public class PlanGenerationWorker {
    private static final Logger log = LoggerFactory.getLogger(PlanGenerationWorker.class);

    private final PlanStore store;
    private final PlanService plans;
    private final ExecutorService generationExecutor;
    private final Semaphore generationSlots;

    @Autowired
    public PlanGenerationWorker(PlanStore store, PlanService plans) {
        this(store, plans, Executors.newVirtualThreadPerTaskExecutor(), new Semaphore(2));
    }

    PlanGenerationWorker(PlanStore store, PlanService plans,
                         ExecutorService generationExecutor, Semaphore generationSlots) {
        this.store = store;
        this.plans = plans;
        this.generationExecutor = generationExecutor;
        this.generationSlots = generationSlots;
    }

    @Scheduled(fixedDelayString = "${plan.generation.poll-ms:500}")
    public void dispatchPlanGeneration() {
        if (!generationSlots.tryAcquire()) return;
        PlanStore.QueuedJob job = store.claimNextGenerationJob();
        if (job == null) {
            generationSlots.release();
            return;
        }
        generationExecutor.submit(() -> {
            try {
                process(job);
            } finally {
                generationSlots.release();
            }
        });
    }

    void process(PlanStore.QueuedJob job) {
        try {
            if (!store.ownsLease(job)) {
                log.info("计划任务租约已失效，跳过旧 worker job={}", job.id());
                return;
            }
            PlanModels.Plan plan = plans.generateWeeklyPlan(
                    job.userId(),
                    job.goal(),
                    job.currentSkills(),
                    job.checkinHistory(),
                    job.traceId() == null ? "plan-job-" + job.id() : job.traceId());
            if (plan == null) {
                store.failGeneration(job, "LLM 返回无效计划或调用失败");
            } else {
                store.completeGeneration(job, plan.id());
            }
        } catch (Exception error) {
            log.error("异步计划生成失败 job={} user={}: {}", job.id(), job.userId(), error.getMessage());
            store.failGeneration(job, safeError(error));
        }
    }

    private String safeError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "计划生成失败";
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    @PreDestroy
    void shutdownGenerationExecutor() {
        generationExecutor.close();
    }
}
