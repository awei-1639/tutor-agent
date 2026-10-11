package com.tutor.coaching.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.platform.llm.JsonGenerationGateway;
import com.tutor.coaching.plan.PlanService;
import com.tutor.identity.profile.ProfileService;
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
    private final com.tutor.coaching.push.CareerGapService careerGaps;
    private final PlanService plans;
    private final InterviewPolicy policy = new InterviewPolicy();
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public InterviewSession(InterviewLlmService interviewer, InterviewSessionRepository sessions,
                            InterviewReportService reports, PlanService plans,
                            com.tutor.coaching.push.CareerGapService careerGaps) {
        this.interviewer = interviewer;
        this.sessions = sessions;
        this.reports = reports;
        this.careerGaps = careerGaps;
        this.plans = plans;
    }

    /** Compatibility constructor retained for database-focused integration tests. */
    public InterviewSession(JdbcTemplate jdbc, InterviewLlmService interviewer,
                            InterviewSessionRepository sessions, InterviewReportService reports,
                            PlanService plans) {
        this(interviewer, sessions, reports, plans, null);
    }

    /** Convenience constructor retained for focused tests with a mock gateway. */
    public InterviewSession(JdbcTemplate jdbc, JsonGenerationGateway gateway, PlanService plans,
                            ProfileService profiles) {
        this(new InterviewLlmService(gateway), new InterviewSessionRepository(jdbc),
                new InterviewReportService(jdbc, new InterviewLlmService(gateway), plans, profiles), plans, null);
    }

    public record InterviewMessage(String sessionId, String status, String message) {}
    public record TranscriptTurn(String speaker, String content) {}
    public record CandidateAsk(String question, String reply, String tip) {}
    public record SessionView(String sessionId, String status, String targetRole, String topic,
                              int mainQuestionsAsked, Instant deadlineAt, List<TranscriptTurn> transcript,
                              /** INTERVIEWING | CANDIDATE_ASK | ENDED —— 前端据此切换输入区文案。 */
                              String phase) {
        SessionView(String sessionId, String status, String targetRole, String topic,
                    int mainQuestionsAsked, Instant deadlineAt, List<TranscriptTurn> transcript) {
            this(sessionId, status, targetRole, topic, mainQuestionsAsked, deadlineAt, transcript, "INTERVIEWING");
        }
    }
    public record HistoryItem(String sessionId, String targetRole, String interviewType, String difficulty,
                              String status, int totalQuestions, double avgScore, Instant createdAt, Instant completedAt,
                              String retestOf, Double scoreDelta) {}
    public record RetestComparison(String sourceSessionId, double baselineAvgScore, double scoreDelta,
                                   List<String> originalFocusAreas, Map<String, Double> baselineDimensions) {
        RetestComparison(String sourceSessionId, double baselineAvgScore, double scoreDelta, List<String> originalFocusAreas) {
            this(sourceSessionId, baselineAvgScore, scoreDelta, originalFocusAreas, null);
        }
    }
    public record Report(int totalQuestions, double avgScore, double scoreConfidence, List<String> strengths,
                         List<String> improvements, List<String> resources, RetestComparison retestComparison,
                         /** 四维平均分 (缺失维度不出现), 供前端雷达图渲染。 */
                         Map<String, Double> dimensions,
                         /** 每维度一句点评。 */
                         Map<String, String> dimensionNotes,
                         /** 反问环节的提问与面试官回答, 让复盘覆盖"最后五分钟"。 */
                         List<CandidateAsk> candidateAsks) {
        Report(int totalQuestions, double avgScore, double scoreConfidence, List<String> strengths,
               List<String> improvements, List<String> resources, RetestComparison retestComparison) {
            this(totalQuestions, avgScore, scoreConfidence, strengths, improvements, resources, retestComparison,
                    Map.of(), Map.of(), List.of());
        }
    }

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
        TopicPlan topicPlan = resolveTopic(userId, role, jd);
        String topic = topicPlan.topic();
        String sessionId = UUID.randomUUID().toString();
        String blueprintId = UUID.randomUUID().toString();
        String gapContext = gapContextFor(userId);
        QuestionSpec firstSpec = generateQuestion(topic, role, jd, type, level, 1, traceId, priorPrompts(retestOf), gapContext);

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
        if ("CANDIDATE_ASK".equals(question.kind())) return evaluateCandidateTurn(session, userAnswer, traceId);

        Scorecard scorecard = scoreAnswer(question.prompt(), question.assessmentContract(), userAnswer, traceId);
        // 当前问题所处的追问层: 主线 0, 追问 1..2。第二层是知识边界探测。
        int currentDepth = sessions.followUpDepthAt(sessionId, question.sequence());
        InterviewPolicy.Decision decision = policy.decide(question.kind(), scorecard.score(), session.mainQuestionCount(),
                mainQuestionLimit(session.durationMinutes()), currentDepth);
        String followUp = null;
        QuestionSpec nextSpec = null;
        if (decision.action() == InterviewPolicy.Action.ASK_FOLLOW_UP) {
            followUp = generateFollowUp(question.prompt(), userAnswer, scorecard.missingPoints(), traceId,
                    Math.max(1, decision.followUpDepth()));
        } else if (decision.action() == InterviewPolicy.Action.ASK_MAIN) {
            nextSpec = generateQuestion(session.topic(), session.targetRole(), session.jobDescription(),
                    session.interviewType(), session.difficulty(), decision.nextMainQuestionCount(), traceId,
                    priorPrompts(session.id(), session.retestOf()), gapContextFor(userId));
        }
        return new TurnEvaluation(scorecard, decision, followUp, nextSpec);
    }

    /**
     * 反问环节: 候选人的输入是他向面试官提的问题, 不参与评分。
     * 明确表示没有问题时不调用 LLM, 直接结束; 否则由面试官身份作答并附一句提问质量点评。
     */
    private TurnEvaluation evaluateCandidateTurn(SessionRow session, String userAnswer, String traceId) {
        boolean hasQuestion = InterviewPolicy.hasQuestion(userAnswer);
        int asked = sessions.countCandidateAsks(session.id());
        InterviewPolicy.Decision decision = policy.decideCandidatePhase(hasQuestion, asked);
        InterviewLlmService.CandidateAnswer candidate = hasQuestion
                ? interviewer.answerCandidateQuestion(session.targetRole(), session.jobDescription(),
                        session.topic(), userAnswer, traceId)
                : null;
        return new TurnEvaluation(null, decision, null, null, candidate);
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

        // 反问环节不走评分链路: 它是候选人的提问, 单独提交。
        if ("CANDIDATE_ASK".equals(question.kind())) {
            return commitCandidateTurn(userId, session, question, userAnswer, requestId, evaluation);
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
            // 第二层追问显式标注为边界探测: 答不上来也是有效信号, 避免用户误以为自己表现差。
            String label = evaluation.decision().followUpDepth() >= InterviewPolicy.MAX_FOLLOW_UP_DEPTH
                    ? "追问（边界探测）" : "追问";
            response = "评分: " + score + "/10\n\n" + label + ": " + followUp;
            advanceSession(sessionId, nextSequence, session.mainQuestionCount(), "IN_PROGRESS");
        } else if (decision.action() == InterviewPolicy.Action.COMPLETE) {
            response = "评分: " + score + "/10\n\n面试结束，复盘报告已准备好。";
            advanceSession(sessionId, session.currentSequence(), session.mainQuestionCount(), "COMPLETED");
        } else if (decision.action() == InterviewPolicy.Action.ASK_CANDIDATE) {
            // 主线问完 -> 进入反问环节: 换候选人向面试官提问。
            String invitation = candidateInvitation(true);
            insertQuestion(sessionId, nextSequence, "CANDIDATE_ASK", invitation, question.skillId(), "{}");
            response = "评分: " + score + "/10\n\n" + invitation;
            advanceSession(sessionId, nextSequence, session.mainQuestionCount(), "IN_PROGRESS");
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

    /**
     * 反问环节的提交: 无评分、不写进分维度统计, 但保留完整问答到复盘报告。
     * session 仍为 IN_PROGRESS 时插入下一道邀请题; 否则直接结束并排队闭环任务。
     */
    private InterviewMessage commitCandidateTurn(long userId, SessionRow session, QuestionRow question, String userAnswer,
                                                 String requestId, TurnEvaluation evaluation) {
        InterviewLlmService.CandidateAnswer candidate = evaluation.candidate();
        StringBuilder response = new StringBuilder();
        if (candidate != null) {
            response.append(candidate.reply());
            if (candidate.tip() != null && !candidate.tip().isBlank()) {
                response.append("\n\n💡 反问点评: ").append(candidate.tip());
            }
        } else {
            response.append("没问题，那我们今天的面试就到这里。");
        }
        boolean complete = evaluation.decision().action() == InterviewPolicy.Action.COMPLETE;
        if (complete) {
            response.append("\n\n面试结束，复盘报告已准备好。");
        } else {
            response.append("\n\n").append(candidateInvitation(false));
        }
        String text = response.toString();
        sessions.recordAnswer(question.id(), userAnswer, null, candidateJson(candidate), requestId, text);
        if (complete) {
            advanceSession(session.id(), question.sequence(), session.mainQuestionCount(), "COMPLETED");
        } else {
            insertQuestion(session.id(), question.sequence() + 1, "CANDIDATE_ASK", candidateInvitation(false),
                    question.skillId(), "{}");
            advanceSession(session.id(), question.sequence() + 1, session.mainQuestionCount(), "IN_PROGRESS");
        }
        String status = complete ? "COMPLETED" : "IN_PROGRESS";
        if (complete) reports.enqueueCompletion(userId, session.id());
        return new InterviewMessage(session.id(), status, text);
    }

    /** first=true 为刚从主线进入反问环节的邀请, 之后是"还有要问的吗"的续问。 */
    private String candidateInvitation(boolean first) {
        return first
                ? "反问环节：技术问题到这里，现在换你问我。可以问团队的技术栈与分工、当前最棘手的技术挑战、"
                        + "新人成长路径或这条线的评估标准；没有问题也可以直接输入「没有问题」结束面试。"
                : "还有其他想了解的吗？继续提问，或输入「没有问题」结束面试。";
    }

    private String candidateJson(InterviewLlmService.CandidateAnswer candidate) {
        if (candidate == null) return "{}";
        try {
            return mapper.writeValueAsString(Map.of("reply", candidate.reply() == null ? "" : candidate.reply(),
                    "tip", candidate.tip() == null ? "" : candidate.tip()));
        } catch (Exception e) { return "{}"; }
    }

    public SessionView session(long userId, String sessionId) {
        SessionRow session = findSession(userId, sessionId);
        List<TranscriptTurn> transcript = sessions.transcript(sessionId);
        String phase = !"IN_PROGRESS".equals(session.status()) ? "ENDED"
                : "CANDIDATE_ASK".equals(sessions.latestQuestionKind(sessionId)) ? "CANDIDATE_ASK" : "INTERVIEWING";
        return new SessionView(session.id(), session.status(), session.targetRole(), session.topic(),
                session.mainQuestionCount(), session.deadlineAt(), transcript, phase);
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

    /** P1-b: 把面试弱项转成学习任务 —— 打通 interview→plan 闭环的证据流。 */
    public List<com.tutor.coaching.plan.PlanModels.PlanTask> planFromWeakSkills(long userId, String sessionId) {
        SessionRow session = findSession(userId, sessionId);
        InterviewStateGuard.requireCompleted(session.status());
        List<String> weak = sessions.weakSkills(sessionId, 7);
        List<String> skills = weak.isEmpty() ? session.skills() : weak;
        String goal = "补齐面试薄弱项: "
                + (session.topic() == null || session.topic().isBlank() ? session.targetRole() : session.topic());
        return plans.createEvidenceTasks(userId, goal, skills);
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

    private TopicPlan resolveTopic(long userId, String targetRole, String jobDescription) {
        List<String> skills = new ArrayList<>();
        // 岗位差距驱动: 目标岗位要求但证据薄弱的技能优先进入考察范围 (P1-a)
        for (com.tutor.coaching.push.CareerGapService.GapCard card : topGapCards(userId)) {
            skills.addAll(card.missing().stream().limit(2).toList());
            skills.addAll(card.speedup());
            if (skills.size() >= 3) break;
        }
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

    /** 画像差距上下文 (供出题 prompt): 已掌握 vs 岗位要求尚缺。无差距数据时返回 null 降级为通用出题。 */
    private String gapContextFor(long userId) {
        var cards = topGapCards(userId);
        if (cards.isEmpty()) return null;
        var card = cards.getFirst();
        if (card.missing().isEmpty() && card.speedup().isEmpty()) return null;
        StringBuilder ctx = new StringBuilder();
        if (!card.matched().isEmpty()) ctx.append("已掌握: ").append(String.join(",", card.matched())).append("; ");
        if (!card.missing().isEmpty()) ctx.append("尚缺: ").append(String.join(",", card.missing())).append("; ");
        if (!card.speedup().isEmpty()) ctx.append("需强化: ").append(String.join(",", card.speedup()));
        return ctx.isEmpty() ? null : ctx.toString().replaceAll("; $", "");
    }

    private List<com.tutor.coaching.push.CareerGapService.GapCard> topGapCards(long userId) {
        if (careerGaps == null) return List.of();
        try {
            return careerGaps.topGaps(userId).stream()
                    .filter(c -> !c.missing().isEmpty() || !c.speedup().isEmpty())
                    .sorted(java.util.Comparator.comparingDouble(
                            com.tutor.coaching.push.CareerGapService.GapCard::coverage).reversed())
                    .limit(1)
                    .toList();
        } catch (Exception ignored) {
            // 画像/岗位数据缺失时保持通用出题, 差距是增强不是前提。
            return List.of();
        }
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
                                          String difficulty, int number, String traceId, List<String> priorQuestions,
                                          String gapContext) {
        return interviewer.generateQuestion(topic, targetRole, jobDescription, interviewType, difficulty, number,
                traceId, priorQuestions, gapContext);
    }

    private String generateFollowUp(String question, String answer, List<String> missingPoints, String traceId, int depth) {
        return interviewer.generateFollowUp(question, answer, missingPoints, traceId, depth);
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
                     String rubricVersion, Map<String, Integer> dimensions, String boundaryNote) {
        Scorecard(int score, List<String> strengths, List<String> missingPoints, double confidence, String rubricVersion) {
            this(score, strengths, missingPoints, confidence, rubricVersion, null, null);
        }
    }
    record TurnEvaluation(Scorecard scorecard, InterviewPolicy.Decision decision, String followUp, QuestionSpec nextSpec,
                          InterviewLlmService.CandidateAnswer candidate) {
        TurnEvaluation(Scorecard scorecard, InterviewPolicy.Decision decision, String followUp, QuestionSpec nextSpec) {
            this(scorecard, decision, followUp, nextSpec, null);
        }
    }
    private record TopicPlan(String topic, List<String> skillIds, String primarySkill) {}
    record QuestionSpec(String question, String dimension, List<String> requiredPoints,
                        List<String> bonusPoints, List<String> criticalErrors) {}
}
