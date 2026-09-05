package com.tutor.coaching.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.config.ExecutorLifecycle;
import com.tutor.coaching.plan.PlanService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** Executes the durable post-interview evidence and learning-plan workflow. */
@Component
final class InterviewCompletionWorker {
    private static final Logger log = LoggerFactory.getLogger(InterviewCompletionWorker.class);
    private static final int MAX_CONCURRENT_JOBS = 2;

    private final InterviewCompletionJobStore jobs;
    private final InterviewLlmService interviewer;
    private final PlanService plans;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor;
    private final Semaphore slots;

    @Autowired
    InterviewCompletionWorker(InterviewCompletionJobStore jobs, InterviewLlmService interviewer, PlanService plans) {
        this(jobs, interviewer, plans, Executors.newVirtualThreadPerTaskExecutor(),
                new Semaphore(MAX_CONCURRENT_JOBS));
    }

    InterviewCompletionWorker(InterviewCompletionJobStore jobs, InterviewLlmService interviewer, PlanService plans,
                               ExecutorService executor, Semaphore slots) {
        this.jobs = jobs;
        this.interviewer = interviewer;
        this.plans = plans;
        this.executor = executor;
        this.slots = slots;
    }

    @Scheduled(fixedDelayString = "${tutor.interview.completion.poll-ms:500}")
    public void dispatch() {
        if (!slots.tryAcquire()) return;
        var job = jobs.claimNext();
        if (job.isEmpty()) {
            slots.release();
            return;
        }
        executor.submit(() -> process(job.get()));
    }

    List<String> createLearningEvidence(long userId, InterviewSession.SessionRow session, String sessionId) {
        List<String> weakSkills = jobs.weakSkills(sessionId);
        for (String skillId : weakSkills) {
            List<InterviewSession.QuestionScore> scores = jobs.scores(sessionId, skillId);
            double average = scores.stream().mapToInt(InterviewSession.QuestionScore::score).average().orElse(0);
            double confidence = scores.stream()
                    .mapToDouble(item -> interviewer.scorecard(item.scorecard()).confidence())
                    .average().orElse(0.5);
            jobs.saveEvidence(userId, sessionId, skillId, average, confidence, evidenceJson(scores));
        }
        return weakSkills;
    }

    @PreDestroy
    void shutdown() {
        ExecutorLifecycle.shutdown(executor, "interview-completion", log);
    }

    void process(InterviewCompletionJobStore.Job job) {
        try {
            InterviewSession.SessionRow session = jobs.session(job.userId(), job.sessionId());
            if (!jobs.ownsLease(job)) {
                log.info("面试闭环任务租约已失效，跳过旧 worker job={}", job.id());
                return;
            }
            List<String> weakSkills = createLearningEvidence(job.userId(), session, job.sessionId());
            if (!jobs.markEvidenceCompleted(job)) {
                log.info("面试闭环任务租约在证据写入后失效，跳过后续副作用 job={}", job.id());
                return;
            }
            if (!weakSkills.isEmpty()) {
                plans.createEvidenceTasks(job.userId(),
                        session.targetRole().isBlank() ? session.topic() : session.targetRole(), weakSkills);
            }
            jobs.markCompleted(job);
        } catch (Exception error) {
            log.error("interview completion job failed id={} session={}: {}",
                    job.id(), job.sessionId(), error.getMessage());
            jobs.markFailure(job, error);
        } finally {
            slots.release();
        }
    }

    private String evidenceJson(List<InterviewSession.QuestionScore> scores) {
        try {
            return mapper.writeValueAsString(scores.stream().map(item -> Map.of(
                    "question", shorten(item.prompt()),
                    "score", item.score(),
                    "missing_points", interviewer.scorecard(item.scorecard()).missingPoints())).toList());
        } catch (Exception error) {
            return "[]";
        }
    }

    private String shorten(String value) {
        return value.length() <= 32 ? value : value.substring(0, 32) + "…";
    }
}
