package com.tutor.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.plan.PlanService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import com.tutor.config.ExecutorLifecycle;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** Builds durable learning evidence and comparable retest signals after an interview. */
@Service
class InterviewReportService {
    private static final Logger log = LoggerFactory.getLogger(InterviewReportService.class);
    private final JdbcTemplate jdbc;
    private final InterviewLlmService interviewer;
    private final PlanService plans;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService completionExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore completionSlots = new Semaphore(2);

    private record CompletionJob(long id, long userId, String sessionId, UUID leaseToken) {}

    record CompletionStatus(String sessionId, String status, int attempts, String lastError, String evidenceStatus, String learningPlanStatus,
                            Instant createdAt, Instant startedAt, Instant finishedAt) {}

    InterviewReportService(JdbcTemplate jdbc, InterviewLlmService interviewer, PlanService plans) {
        this.jdbc = jdbc;
        this.interviewer = interviewer;
        this.plans = plans;
    }

    InterviewSession.Report buildReport(long userId, InterviewSession.SessionRow session, String sessionId) {
        List<InterviewSession.QuestionScore> scores = jdbc.query("""
                SELECT prompt, score, COALESCE(scorecard::text, '{}') FROM interview_questions
                WHERE session_id=? AND score IS NOT NULL ORDER BY sequence
                """, (rs, i) -> new InterviewSession.QuestionScore(rs.getString(1), rs.getInt(2), rs.getString(3)), sessionId);
        double average = scores.stream().mapToInt(InterviewSession.QuestionScore::score).average().orElse(0);
        List<String> strengths = new java.util.ArrayList<>();
        List<String> improvements = new java.util.ArrayList<>();
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
        jdbc.update("""
                INSERT INTO interview_completion_jobs (user_id, session_id)
                VALUES (?, ?)
                ON CONFLICT (session_id) DO NOTHING
                """, userId, sessionId);
    }

    CompletionStatus completionStatus(long userId, String sessionId) {
        return jdbc.query("""
                SELECT session_id, status, attempts, last_error, evidence_status, learning_plan_status, created_at, started_at, finished_at
                FROM interview_completion_jobs WHERE user_id=? AND session_id=?
                """, (rs, i) -> new CompletionStatus(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant(), rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(),
                rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant()), userId, sessionId).stream().findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "面试闭环任务不存在"));
    }

    @Scheduled(fixedDelayString = "${tutor.interview.completion.poll-ms:500}")
    void dispatchCompletion() {
        if (!completionSlots.tryAcquire()) return;
        CompletionJob job = claimNextCompletion();
        if (job == null) {
            completionSlots.release();
            return;
        }
        completionExecutor.submit(() -> {
            try {
                InterviewSession.SessionRow session = loadSession(job.userId(), job.sessionId());
                if (!ownsLease(job)) {
                    log.info("面试闭环任务租约已失效，跳过旧 worker job={}", job.id());
                    return;
                }
                List<String> weakSkills = createLearningEvidence(job.userId(), session, job.sessionId());
                int evidenceMarked = jdbc.update("""
                        UPDATE interview_completion_jobs SET evidence_status='completed'
                        WHERE id=? AND status='running' AND lease_token=? AND lease_until > now()
                        """, job.id(), job.leaseToken());
                if (evidenceMarked != 1) {
                    log.info("面试闭环任务租约在证据写入后失效，跳过后续副作用 job={}", job.id());
                    return;
                }
                createLearningPlan(job.userId(), session, weakSkills);
                jdbc.update("""
                        UPDATE interview_completion_jobs
                        SET status='completed', evidence_status='completed', learning_plan_status='completed', finished_at=now(),
                            last_error=NULL, lease_token=NULL, lease_until=NULL
                        WHERE id=? AND status='running' AND lease_token=? AND lease_until > now()
                        """, job.id(), job.leaseToken());
            } catch (Exception error) {
                log.error("interview completion job failed id={} session={}: {}", job.id(), job.sessionId(), error.getMessage());
                markCompletionFailure(job, error);
            } finally {
                completionSlots.release();
            }
        });
    }

    private CompletionJob claimNextCompletion() {
        return jdbc.query("""
                WITH next_job AS (
                    SELECT id FROM interview_completion_jobs
                    WHERE (status='queued' AND attempts < 3)
                       OR (status='running' AND (lease_until IS NULL OR lease_until < now()) AND attempts < 3)
                    ORDER BY id
                    FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE interview_completion_jobs j
                SET status='running', attempts=attempts+1, started_at=now(), last_error=NULL,
                    lease_token=?, lease_until=now() + interval '10 minutes'
                FROM next_job
                WHERE j.id=next_job.id
                RETURNING j.id, j.user_id, j.session_id, j.lease_token
                """, (rs, i) -> new CompletionJob(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getObject(4, UUID.class)),
                UUID.randomUUID()).stream().findFirst().orElse(null);
    }

    private void markCompletionFailure(CompletionJob job, Exception error) {
        String message = error.getMessage() == null || error.getMessage().isBlank() ? "面试闭环任务失败" : error.getMessage();
        if (message.length() > 500) message = message.substring(0, 500);
        jdbc.update("""
                UPDATE interview_completion_jobs
                SET status=CASE WHEN attempts >= 3 THEN 'failed' ELSE 'queued' END,
                    learning_plan_status=CASE WHEN attempts >= 3 AND evidence_status='completed' THEN 'failed' ELSE learning_plan_status END,
                    last_error=?, finished_at=CASE WHEN attempts >= 3 THEN now() ELSE NULL END,
                    lease_token=NULL, lease_until=NULL
                WHERE id=? AND status='running' AND lease_token=? AND lease_until > now()
                """, message, job.id(), job.leaseToken());
    }

    private boolean ownsLease(CompletionJob job) {
        Integer active = jdbc.queryForObject("""
                SELECT count(*) FROM interview_completion_jobs
                WHERE id=? AND status='running' AND lease_token=? AND lease_until > now()
                """, Integer.class, job.id(), job.leaseToken());
        return active != null && active == 1;
    }

    private InterviewSession.SessionRow loadSession(long userId, String sessionId) {
        return jdbc.query("""
                SELECT s.id, s.target_role, s.topic, s.status, s.current_question_sequence, s.main_question_count,
                       COALESCE(array_to_string(s.skill_ids, ','), ''), COALESCE(b.job_description, ''),
                       COALESCE(b.interview_type, 'technical'), COALESCE(b.difficulty, 'MID'), COALESCE(b.duration_minutes, 45), s.deadline_at, s.retest_of
                FROM interview_sessions s LEFT JOIN interview_blueprints b ON b.id=s.blueprint_id
                WHERE s.id=? AND s.user_id=?
                """, (rs, i) -> mapSession(rs), sessionId, userId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("interview session missing: " + sessionId));
    }

    List<String> createLearningEvidence(long userId, InterviewSession.SessionRow session, String sessionId) {
        List<String> weakSkills = jdbc.query("""
                SELECT DISTINCT COALESCE(skill_id, '') FROM interview_questions
                WHERE session_id=? AND score IS NOT NULL AND score < 7 AND skill_id IS NOT NULL
                """, (rs, i) -> rs.getString(1), sessionId);
        for (String skillId : weakSkills) {
            List<InterviewSession.QuestionScore> scores = jdbc.query("""
                    SELECT prompt, score, COALESCE(scorecard::text, '{}') FROM interview_questions
                    WHERE session_id=? AND skill_id=? AND score IS NOT NULL
                    """, (rs, i) -> new InterviewSession.QuestionScore(rs.getString(1), rs.getInt(2), rs.getString(3)), sessionId, skillId);
            double average = scores.stream().mapToInt(InterviewSession.QuestionScore::score).average().orElse(0);
            double confidence = scores.stream().mapToDouble(item -> interviewer.scorecard(item.scorecard()).confidence()).average().orElse(0.5);
            jdbc.update("""
                    INSERT INTO interview_skill_evidence (user_id, session_id, skill_id, average_score, confidence, evidence)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT (session_id, skill_id) DO UPDATE SET average_score=EXCLUDED.average_score,
                      confidence=EXCLUDED.confidence, evidence=EXCLUDED.evidence
                    """, userId, sessionId, skillId, average, confidence, evidenceJson(scores));
        }
        return weakSkills;
    }

    private void createLearningPlan(long userId, InterviewSession.SessionRow session, List<String> weakSkills) {
        if (!weakSkills.isEmpty()) {
            plans.createEvidenceTasks(userId, session.targetRole().isBlank() ? session.topic() : session.targetRole(), weakSkills);
        }
    }

    InterviewSession.RetestComparison retestComparison(long userId, InterviewSession.SessionRow session, double currentAverage) {
        if (session.retestOf() == null || session.retestOf().isBlank()) return null;
        List<InterviewSession.SessionRow> sources = jdbc.query("""
                SELECT s.id, s.target_role, s.topic, s.status, s.current_question_sequence, s.main_question_count,
                       COALESCE(array_to_string(s.skill_ids, ','), ''), COALESCE(b.job_description, ''),
                       COALESCE(b.interview_type, 'technical'), COALESCE(b.difficulty, 'MID'), COALESCE(b.duration_minutes, 45), s.deadline_at, s.retest_of
                FROM interview_sessions s LEFT JOIN interview_blueprints b ON b.id=s.blueprint_id
                WHERE s.id=? AND s.user_id=?
                """, (rs, i) -> mapSession(rs), session.retestOf(), userId);
        if (sources.isEmpty() || !"COMPLETED".equals(sources.getFirst().status())) return null;
        List<InterviewSession.QuestionScore> sourceScores = jdbc.query("""
                SELECT prompt, score, COALESCE(scorecard::text, '{}') FROM interview_questions
                WHERE session_id=? AND score IS NOT NULL ORDER BY sequence
                """, (rs, i) -> new InterviewSession.QuestionScore(rs.getString(1), rs.getInt(2), rs.getString(3)), session.retestOf());
        if (sourceScores.isEmpty()) return null;
        double baseline = sourceScores.stream().mapToInt(InterviewSession.QuestionScore::score).average().orElse(0);
        List<String> focusAreas = sourceScores.stream().filter(item -> item.score() < 7)
                .flatMap(item -> interviewer.scorecard(item.scorecard()).missingPoints().stream()).distinct().limit(5).toList();
        return new InterviewSession.RetestComparison(session.retestOf(), baseline, currentAverage - baseline, focusAreas);
    }

    private String evidenceJson(List<InterviewSession.QuestionScore> scores) {
        try {
            return mapper.writeValueAsString(scores.stream().map(item -> Map.of("question", shorten(item.prompt()),
                    "score", item.score(), "missing_points", interviewer.scorecard(item.scorecard()).missingPoints())).toList());
        } catch (Exception e) {
            return "[]";
        }
    }

    private InterviewSession.SessionRow mapSession(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<String> skills = rs.getString(7).isBlank() ? List.of() : Arrays.asList(rs.getString(7).split(","));
        return new InterviewSession.SessionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6),
                skills, rs.getString(8), rs.getString(9), rs.getString(10), rs.getInt(11), rs.getTimestamp(12).toInstant(), rs.getString(13));
    }

    private String shorten(String value) {
        return value.length() <= 32 ? value : value.substring(0, 32) + "…";
    }

    @PreDestroy
    void shutdownCompletionExecutor() {
        ExecutorLifecycle.shutdown(completionExecutor, "interview-completion", log);
    }
}
