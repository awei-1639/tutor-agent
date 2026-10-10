package com.tutor.coaching.interview;

import com.tutor.coaching.plan.PlanService;
import com.tutor.identity.profile.ProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    InterviewReportService(JdbcTemplate jdbc, InterviewLlmService interviewer, PlanService plans,
                           ProfileService profiles) {
        this(jdbc, interviewer, plans, profiles,
                new InterviewCompletionJobStore(jdbc, new com.tutor.platform.jobs.LeasedJobQueue(jdbc)));
    }

    private InterviewReportService(JdbcTemplate jdbc, InterviewLlmService interviewer, PlanService plans,
                                   ProfileService profiles, InterviewCompletionJobStore completionJobs) {
        this(interviewer, new InterviewSessionRepository(jdbc), completionJobs,
                new InterviewCompletionWorker(completionJobs, interviewer, plans, profiles));
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
        Map<String, Double> dimensions = averageDimensions(scores);
        return new InterviewSession.Report(scores.size(), average, confidence, strengths, improvements, List.of("skill:" + session.topic()),
                retestComparison(userId, session, average), dimensions, dimensionNotes(dimensions),
                sessions.candidateAsks(sessionId));
    }

    /** 四维平均分; 某维完全缺失时不出现在结果里, 前端雷达图按实际维度渲染。 */
    private Map<String, Double> averageDimensions(List<InterviewSession.QuestionScore> scores) {
        Map<String, java.util.List<Integer>> collected = new java.util.LinkedHashMap<>();
        for (InterviewSession.QuestionScore item : scores) {
            Map<String, Integer> dims = interviewer.scorecard(item.scorecard()).dimensions();
            if (dims == null) continue;
            dims.forEach((key, value) -> collected.computeIfAbsent(key, k -> new ArrayList<>()).add(value));
        }
        Map<String, Double> result = new java.util.LinkedHashMap<>();
        for (String key : InterviewLlmService.DIMENSION_KEYS) {
            java.util.List<Integer> values = collected.get(key);
            if (values == null || values.isEmpty()) continue;
            double avg = values.stream().mapToInt(Integer::intValue).average().orElse(0);
            result.put(key, Math.round(avg * 10.0) / 10.0);
        }
        return result;
    }

    /** 规则化维度点评: 不额外消耗 LLM, 分档给一句可执行建议。 */
    private Map<String, String> dimensionNotes(Map<String, Double> dimensions) {
        if (dimensions.isEmpty()) return Map.of();
        Map<String, String> labels = Map.of(
                "technical_depth", "技术深度",
                "structure", "结构与表达",
                "boundary_awareness", "边界意识",
                "role_fit", "岗位匹配");
        Map<String, String> result = new java.util.LinkedHashMap<>();
        String weakest = null;
        double weakestValue = Double.MAX_VALUE;
        for (String key : InterviewLlmService.DIMENSION_KEYS) {
            Double value = dimensions.get(key);
            if (value == null) continue;
            if (value < weakestValue) { weakestValue = value; weakest = key; }
            if (value >= 8) result.put(key, "表现扎实，能把原理讲透并结合场景给出权衡。");
            else if (value >= 6) result.put(key, "基本达标，补上边界条件与失败场景会更稳。");
            else result.put(key, "偏薄弱，建议按下方补强计划针对性练习。");
        }
        if (weakest != null) {
            result.merge(weakest, "本场最薄弱维度：" + labels.getOrDefault(weakest, weakest) + "，提升它性价比最高。",
                    (existing, added) -> existing + " " + added);
        }
        return result;
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
        return new InterviewSession.RetestComparison(session.retestOf(), baseline, currentAverage - baseline, focusAreas,
                averageDimensions(sourceScores));
    }

    private String shorten(String value) {
        return value.length() <= 32 ? value : value.substring(0, 32) + "…";
    }

    void shutdownCompletionExecutor() {
        completionWorker.shutdown();
    }
}
