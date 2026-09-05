package com.tutor.coaching.interview;

import com.tutor.coaching.plan.PlanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.time.Instant;

/** Builds durable learning evidence and comparable retest signals after an interview. */
@Service
class InterviewReportService {
    private final InterviewSessionRepository sessions;
    private final InterviewLlmService interviewer;
    private final InterviewCompletionJobStore completionJobs;
    private final InterviewCompletionWorker completionWorker;

    record CompletionStatus(String sessionId, String status, int attempts, String lastError, String evidenceStatus, String learningPlanStatus,
                            Instant createdAt, Instant startedAt, Instant finishedAt) {}

    @Autowired
    InterviewReportService(InterviewLlmService interviewer, InterviewSessionRepository sessions,
                           InterviewCompletionJobStore completionJobs,
                           InterviewCompletionWorker completionWorker) {
        this.sessions = sessions;
        this.interviewer = interviewer;
        this.completionJobs = completionJobs;
        this.completionWorker = completionWorker;
    }

    /** Compatibility constructor retained for focused and database integration tests. */
    InterviewReportService(JdbcTemplate jdbc, InterviewLlmService interviewer, PlanService plans) {
        this(jdbc, interviewer, plans, new InterviewCompletionJobStore(jdbc));
    }

    private InterviewReportService(JdbcTemplate jdbc, InterviewLlmService interviewer, PlanService plans,
                                   InterviewCompletionJobStore completionJobs) {
        this(interviewer, new InterviewSessionRepository(jdbc), completionJobs,
                new InterviewCompletionWorker(completionJobs, interviewer, plans));
    }

    InterviewSession.Report buildReport(long userId, InterviewSession.SessionRow session, String sessionId) {
        List<InterviewSession.QuestionScore> scores = sessions.scoredQuestions(sessionId);
        double average = scores.stream().mapToInt(InterviewSession.QuestionScore::score).average().orElse(0);
        List<String> strengths = new ArrayList<>();
        List<String> improvements = new ArrayList<>();
        for (InterviewSession.QuestionScore item : scores) {
            InterviewSession.Scorecard card = interviewer.scorecard(item.scorecard());
            if (item.score() >= 7) {
                if (card.strengths().isEmpty()) strengths.add("“" + shorten(item.prompt()) + "”回答表现较好");
                else strengths.addAll(card.strengths());
            } else if (card.missingPoints().isEmpty()) {
                improvements.add("建议复盘“" + shorten(item.prompt()) + "”涉及的关键原理和实践边界");
            } else {
                improvements.addAll(card.missingPoints());
            }
        }
        if (strengths.isEmpty()) strengths.add("已完成面试，可从基础概念和项目实践开始巩固");
        if (improvements.isEmpty()) improvements.add("建议通过不同场景的复测验证知识迁移能力");
        double confidence = scores.stream().mapToDouble(item -> interviewer.scorecard(item.scorecard()).confidence()).average().orElse(0);
        return new InterviewSession.Report(scores.size(), average, confidence, strengths, improvements, List.of("skill:" + session.topic()),
                retestComparison(userId, session, average));
    }

    /** Enqueues once after the interview transaction reaches a terminal state. */
    void enqueueCompletion(long userId, String sessionId) {
        completionJobs.enqueue(userId, sessionId);
    }

    CompletionStatus completionStatus(long userId, String sessionId) {
        return completionJobs.status(userId, sessionId).map(row -> new CompletionStatus(row.sessionId(), row.status(),
                row.attempts(), row.lastError(), row.evidenceStatus(), row.learningPlanStatus(), row.createdAt(),
                row.startedAt(), row.finishedAt())).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "面试闭环任务不存在"));
    }

    List<String> createLearningEvidence(long userId, InterviewSession.SessionRow session, String sessionId) {
        return completionWorker.createLearningEvidence(userId, session, sessionId);
    }

    InterviewSession.RetestComparison retestComparison(long userId, InterviewSession.SessionRow session, double currentAverage) {
        if (session.retestOf() == null || session.retestOf().isBlank()) return null;
        InterviewSession.SessionRow source = sessions.findSessionIfPresent(userId, session.retestOf(), false).orElse(null);
        if (source == null || !"COMPLETED".equals(source.status())) return null;
        List<InterviewSession.QuestionScore> sourceScores = sessions.scoredQuestions(session.retestOf());
        if (sourceScores.isEmpty()) return null;
        double baseline = sourceScores.stream().mapToInt(InterviewSession.QuestionScore::score).average().orElse(0);
        List<String> focusAreas = sourceScores.stream().filter(item -> item.score() < 7)
                .flatMap(item -> interviewer.scorecard(item.scorecard()).missingPoints().stream()).distinct().limit(5).toList();
        return new InterviewSession.RetestComparison(session.retestOf(), baseline, currentAverage - baseline, focusAreas);
    }

    private String shorten(String value) {
        return value.length() <= 32 ? value : value.substring(0, 32) + "…";
    }

    void shutdownCompletionExecutor() {
        completionWorker.shutdown();
    }
}
