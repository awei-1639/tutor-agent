package com.tutor.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.llm.JsonGenerationGateway;
import com.tutor.plan.PlanService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;

/**
 * Durable interview runtime. The database is the source of truth: it keeps the
 * session recoverable after a restart and scopes every operation to its owner.
 */
@Service
public class InterviewSession {
    private static final int DEFAULT_MAIN_QUESTION_LIMIT = 5;

    private final InterviewLlmService interviewer;
    private final InterviewSessionRepository sessions;
    private final InterviewReportService reports;
    private final InterviewPolicy policy = new InterviewPolicy();
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public InterviewSession(InterviewLlmService interviewer, InterviewSessionRepository sessions,
                            InterviewReportService reports, PlanService plans) {
        this.interviewer = interviewer;
        this.sessions = sessions;
        this.reports = reports;
    }

    /** Compatibility constructor retained for database-focused integration tests. */
    public InterviewSession(JdbcTemplate jdbc, InterviewLlmService interviewer,
                            InterviewSessionRepository sessions, InterviewReportService reports,
                            PlanService plans) {
        this(interviewer, sessions, reports, plans);
    }

    /** Convenience constructor retained for focused tests with a mock gateway. */
    public InterviewSession(JdbcTemplate jdbc, JsonGenerationGateway gateway, PlanService plans) {
        this(new InterviewLlmService(gateway), new InterviewSessionRepository(jdbc),
                new InterviewReportService(jdbc, new InterviewLlmService(gateway), plans), plans);
    }

    public record InterviewMessage(String sessionId, String status, String message) {}
    public record TranscriptTurn(String speaker, String content) {}
    public record SessionView(String sessionId, String status, String targetRole, String topic,
                              int mainQuestionsAsked, Instant deadlineAt, List<TranscriptTurn> transcript) {}
    public record HistoryItem(String sessionId, String targetRole, String interviewType, String difficulty,
                              String status, int totalQuestions, double avgScore, Instant createdAt, Instant completedAt,
                              String retestOf, Double scoreDelta) {}
    public record RetestComparison(String sourceSessionId, double baselineAvgScore, double scoreDelta,
                                   List<String> originalFocusAreas) {}
    public record Report(int totalQuestions, double avgScore, double scoreConfidence, List<String> strengths,
                         List<String> improvements, List<String> resources, RetestComparison retestComparison) {}

    /** Creates a user-scoped session and its first question. */
    public InterviewMessage open(long userId, String targetRole, String jobDescription, String interviewType,
                                 String difficulty, Integer durationMinutes, String traceId) {
        return open(userId, targetRole, jobDescription, interviewType, difficulty, durationMinutes, traceId, null);
    }

    private InterviewMessage open(long userId, String targetRole, String jobDescription, String interviewType,
                                  String difficulty, Integer durationMinutes, String traceId, String retestOf) {
        String role = targetRole == null ? "" : targetRole.trim();
        String jd = jobDescription == null ? "" : jobDescription.trim();
        String type = interviewType == null || interviewType.isBlank() ? "technical" : interviewType;
        String level = difficulty == null || difficulty.isBlank() ? "MID" : difficulty;
        int duration = durationMinutes == null ? 45 : durationMinutes;
        TopicPlan topicPlan = resolveTopic(role, jd);
        String topic = topicPlan.topic();
        String sessionId = UUID.randomUUID().toString();
        String blueprintId = UUID.randomUUID().toString();
        QuestionSpec firstSpec = generateQuestion(topic, role, jd, type, level, 1, traceId, priorPrompts(retestOf));

        sessions.insertBlueprint(blueprintId, userId, role, topic, topicPlan.skillIds(),
                blueprintJson(role, topicPlan.skillIds(), type, level, duration), jd, type, level, duration);
        sessions.insertSession(sessionId, userId, role, topic, blueprintId,
                topicPlan.skillIds(), retestOf, duration);
        insertQuestion(sessionId, 1, "MAIN", firstSpec.question(), topicPlan.primarySkill(), contractJson(firstSpec));
        return new InterviewMessage(sessionId, "IN_PROGRESS", "面试主题: " + topic + "\n\n问题 1: " + firstSpec.question());
    }

    /**
     * A request id makes answer submission safely retryable. Row locks serialize
     * concurrent browser tabs or retry attempts for the same interview.
     */
    public InterviewMessage answer(long userId, String sessionId, String userAnswer, String requestId, String traceId) {
        TurnEvaluation evaluation = evaluateTurn(userId, sessionId, userAnswer, traceId);
        return commitTurn(userId, sessionId, userAnswer, requestId, evaluation);
    }

    /** Executes all uncertain LLM work before acquiring any database lock. */
    TurnEvaluation evaluateTurn(long userId, String sessionId, String userAnswer, String traceId) {
        SessionRow session = findSession(userId, sessionId);
        InterviewStateGuard.requireInProgress(session.status());
        if (!Instant.now().isBefore(session.deadlineAt())) throw new ResponseStatusException(HttpStatus.CONFLICT, "面试时间已到，请结束面试查看复盘");
        QuestionRow question = sessions.findCurrentQuestion(sessionId, session.currentSequence());
        if (question.answer() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "当前问题已经提交过回答");
        Scorecard scorecard = scoreAnswer(question.prompt(), question.assessmentContract(), userAnswer, traceId);
        InterviewPolicy.Decision decision = policy.decide(question.kind(), scorecard.score(), session.mainQuestionCount(),
                mainQuestionLimit(session.durationMinutes()));
        String followUp = null;
        QuestionSpec nextSpec = null;
        if (decision.action() == InterviewPolicy.Action.ASK_FOLLOW_UP) {
            followUp = generateFollowUp(question.prompt(), userAnswer, scorecard.missingPoints(), traceId);
        } else if (decision.action() == InterviewPolicy.Action.ASK_MAIN) {
            nextSpec = generateQuestion(session.topic(), session.targetRole(), session.jobDescription(),
                    session.interviewType(), session.difficulty(), decision.nextMainQuestionCount(), traceId,
                    priorPrompts(session.id(), session.retestOf()));
        }
        return new TurnEvaluation(scorecard, decision, followUp, nextSpec);
    }

    /** Short, locked commit: it never calls an LLM and therefore remains safe under provider latency. */
    @Transactional
    public InterviewMessage commitTurn(long userId, String sessionId, String userAnswer, String requestId, TurnEvaluation evaluation) {
        SessionRow session = findSessionForUpdate(userId, sessionId);
        InterviewStateGuard.requireInProgress(session.status());
        if (!Instant.now().isBefore(session.deadlineAt())) {
            advanceSession(sessionId, session.currentSequence(), session.mainQuestionCount(), "COMPLETED");
            reports.enqueueCompletion(userId, sessionId);
            return new InterviewMessage(sessionId, "COMPLETED", "面试时间已到，已生成当前进度的复盘报告。");
        }
        QuestionRow question = findCurrentQuestionForUpdate(sessionId, session.currentSequence());
        if (question.answer() != null) {
            if (requestId.equals(question.answerRequestId())) {
                return new InterviewMessage(sessionId, session.status(), question.responseMessage());
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前问题已经提交过回答");
        }

        Scorecard scorecard = evaluation.scorecard();
        int score = scorecard.score();
        String response;
        int nextSequence = question.sequence() + 1;
        InterviewPolicy.Decision decision = evaluation.decision();
        if (decision.action() == InterviewPolicy.Action.ASK_FOLLOW_UP) {
            String followUp = evaluation.followUp();
            if (followUp == null || followUp.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "追问生成无效，请重试");
            insertQuestion(sessionId, nextSequence, "FOLLOW_UP", followUp, question.skillId(), question.assessmentContract());
            response = "评分: " + score + "/10\n\n追问: " + followUp;
            advanceSession(sessionId, nextSequence, session.mainQuestionCount(), "IN_PROGRESS");
        } else if (decision.action() == InterviewPolicy.Action.COMPLETE) {
            response = "评分: " + score + "/10\n\n面试结束，复盘报告已准备好。";
            advanceSession(sessionId, session.currentSequence(), session.mainQuestionCount(), "COMPLETED");
        } else {
            int nextMainCount = decision.nextMainQuestionCount();
            QuestionSpec nextSpec = evaluation.nextSpec();
            if (nextSpec == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "下一题生成无效，请重试");
            insertQuestion(sessionId, nextSequence, "MAIN", nextSpec.question(), session.primarySkill(), contractJson(nextSpec));
            response = "评分: " + score + "/10\n\n问题 " + nextMainCount + ": " + nextSpec.question();
            advanceSession(sessionId, nextSequence, nextMainCount, "IN_PROGRESS");
        }
        sessions.recordAnswer(question.id(), userAnswer, score, scorecardJson(scorecard), requestId, response);
        String status = decision.action() == InterviewPolicy.Action.COMPLETE ? "COMPLETED" : "IN_PROGRESS";
        if ("COMPLETED".equals(status)) reports.enqueueCompletion(userId, sessionId);
        return new InterviewMessage(sessionId, status, response);
    }

    public SessionView session(long userId, String sessionId) {
        SessionRow session = findSession(userId, sessionId);
        List<TranscriptTurn> transcript = sessions.transcript(sessionId);
        return new SessionView(session.id(), session.status(), session.targetRole(), session.topic(),
                session.mainQuestionCount(), session.deadlineAt(), transcript);
    }

    /** Creates a comparable new session from a completed source session. */
    public InterviewMessage retest(long userId, String sourceSessionId, String traceId) {
        SessionRow source = findSession(userId, sourceSessionId);
        InterviewStateGuard.requireCompleted(source.status());
        return open(userId, source.targetRole(), source.jobDescription(), source.interviewType(), source.difficulty(),
                source.durationMinutes(), traceId, source.id());
    }

    @Transactional
    public InterviewMessage cancel(long userId, String sessionId) {
        SessionRow session = findSessionForUpdate(userId, sessionId);
        InterviewStateGuard.requireCancellable(session.status());
        advanceSession(sessionId, session.currentSequence(), session.mainQuestionCount(), "CANCELLED");
        reports.enqueueCompletion(userId, sessionId);
        return new InterviewMessage(sessionId, "CANCELLED", "已结束本场面试，复盘报告已按已完成题目生成。");
    }

    public Report report(long userId, String sessionId) {
        SessionRow session = findSession(userId, sessionId);
        return reports.buildReport(userId, session, sessionId);
    }

    public InterviewReportService.CompletionStatus completionStatus(long userId, String sessionId) {
        findSession(userId, sessionId);
        return reports.completionStatus(userId, sessionId);
    }

    /** Captures a user-visible calibration signal without exposing reports across accounts. */
    public void feedback(long userId, String sessionId, String rating, String reason) {
        SessionRow session = findSession(userId, sessionId);
        if ("IN_PROGRESS".equals(session.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请在面试结束后再反馈评分准确性");
        }
        sessions.recordFeedback(userId, sessionId, rating, reason);
    }

    /** Recent sessions are deliberately owner-scoped; reports never need a global admin lookup. */
    public List<HistoryItem> history(long userId, int limit) {
        return sessions.history(userId, limit);
    }

    private TopicPlan resolveTopic(String targetRole, String jobDescription) {
        List<String> skills = new ArrayList<>();
        try {
            skills.addAll(sessions.requiredSkillsForRole(targetRole));
        } catch (Exception ignored) {
            // Jobs are an enhancement, not a prerequisite for starting an interview.
        }
        skills.addAll(extractJdSkills(jobDescription));
        if (skills.isEmpty()) skills.add("通用算法与系统设计");
        List<String> normalized = skills.stream().map(this::normalizeSkill).distinct().limit(3).toList();
        return new TopicPlan(String.join(", ", skills), normalized, normalized.getFirst());
    }

    private void insertQuestion(String sessionId, int sequence, String kind, String prompt, String skillId, String contract) {
        sessions.insertQuestion(sessionId, sequence, kind, prompt, skillId, contract);
    }

    private void advanceSession(String sessionId, int sequence, int mainQuestionCount, String status) {
        sessions.advanceSession(sessionId, sequence, mainQuestionCount, status);
    }

    private SessionRow findSession(long userId, String sessionId) {
        return sessions.findSession(userId, sessionId, false);
    }

    private SessionRow findSessionForUpdate(long userId, String sessionId) {
        return sessions.findSession(userId, sessionId, true);
    }

    private QuestionRow findCurrentQuestionForUpdate(String sessionId, int sequence) {
        return sessions.findCurrentQuestionForUpdate(sessionId, sequence);
    }

    private QuestionSpec generateQuestion(String topic, String targetRole, String jobDescription, String interviewType,
                                          String difficulty, int number, String traceId, List<String> priorQuestions) {
        return interviewer.generateQuestion(topic, targetRole, jobDescription, interviewType, difficulty, number, traceId, priorQuestions);
    }

    private String generateFollowUp(String question, String answer, List<String> missingPoints, String traceId) {
        return interviewer.generateFollowUp(question, answer, missingPoints, traceId);
    }

    private Scorecard scoreAnswer(String question, String assessmentContract, String answer, String traceId) {
        return interviewer.scoreAnswer(question, assessmentContract, answer, traceId);
    }

    private String blueprintJson(String role, List<String> skills, String type, String difficulty, int duration) {
        try {
            return mapper.writeValueAsString(Map.of("version", "v1", "target_role", role, "round", "technical_screen",
                    "main_question_limit", mainQuestionLimit(duration), "skills", skills, "interview_type", type,
                    "difficulty", difficulty, "duration_minutes", duration));
        } catch (Exception e) { return "{}"; }
    }

    private String scorecardJson(Scorecard scorecard) {
        try { return mapper.writeValueAsString(scorecard); } catch (Exception e) { return "{}"; }
    }

    private String contractJson(QuestionSpec question) {
        try { return mapper.writeValueAsString(question); } catch (Exception e) { return "{}"; }
    }

    private List<String> extractJdSkills(String jobDescription) {
        if (jobDescription == null || jobDescription.isBlank()) return List.of();
        String lower = jobDescription.toLowerCase();
        List<String> known = List.of("Java", "Spring", "MySQL", "Redis", "Kafka", "RabbitMQ", "Docker", "Kubernetes",
                "React", "TypeScript", "Python", "机器学习", "深度学习", "LLM", "RAG", "Linux", "微服务", "系统设计");
        return known.stream().filter(skill -> lower.contains(skill.toLowerCase())).toList();
    }

    private List<String> priorPrompts(String sessionId) {
        return sessions.priorPrompts(sessionId);
    }

    private List<String> priorPrompts(String currentSessionId, String sourceSessionId) {
        List<String> prompts = new ArrayList<>(priorPrompts(currentSessionId));
        if (sourceSessionId != null && !sourceSessionId.isBlank()) prompts.addAll(priorPrompts(sourceSessionId));
        return prompts.stream().distinct().toList();
    }

    private String normalizeSkill(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.startsWith("skill:") ? trimmed : "skill:" + trimmed;
    }

    private int mainQuestionLimit(int durationMinutes) {
        if (durationMinutes < 30) return 2;
        if (durationMinutes < 45) return 3;
        if (durationMinutes < 60) return DEFAULT_MAIN_QUESTION_LIMIT;
        if (durationMinutes < 90) return 6;
        return 8;
    }

    record SessionRow(String id, String targetRole, String topic, String status,
                      int currentSequence, int mainQuestionCount, List<String> skills, String jobDescription,
                      String interviewType, String difficulty, int durationMinutes, Instant deadlineAt, String retestOf) {
        String primarySkill() { return skills.isEmpty() ? "skill:通用算法与系统设计" : skills.getFirst(); }
    }
    record QuestionRow(long id, int sequence, String kind, String prompt, String answer,
                       String answerRequestId, String responseMessage, String skillId, String assessmentContract) {}
    record QuestionScore(String prompt, int score, String scorecard) {}
    record Scorecard(int score, List<String> strengths, List<String> missingPoints, double confidence,
                     String rubricVersion) {}
    record TurnEvaluation(Scorecard scorecard, InterviewPolicy.Decision decision, String followUp, QuestionSpec nextSpec) {}
    private record TopicPlan(String topic, List<String> skillIds, String primarySkill) {}
    record QuestionSpec(String question, String dimension, List<String> requiredPoints,
                        List<String> bonusPoints, List<String> criticalErrors) {}
}
