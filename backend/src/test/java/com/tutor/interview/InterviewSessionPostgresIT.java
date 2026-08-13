package com.tutor.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.admin.AdminService;
import com.tutor.auth.AuthService;
import com.tutor.auth.JwtService;
import com.tutor.contract.Purpose;
import com.tutor.llm.LlmGateway;
import com.tutor.plan.PlanService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs the durable interview lifecycle against the real schema. This stays opt-in
 * because it requires Docker: mvn test -DrunIntegrationTests=true.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "runIntegrationTests", matches = "true")
class InterviewSessionPostgresIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private AuthService auth;
    private InterviewSession interviews;
    private InterviewReportService reports;

    @BeforeAll
    void migrateAndCreateService() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        auth = new AuthService(jdbc, new JwtService("test_only_jwt_secret_at_least_32_characters_long"));

        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.chatJson(eq(Purpose.PLAN), anyList(), anyString())).thenReturn("""
                {"question":"请说明缓存击穿的处理方案","required_points":["互斥重建","过期策略"],
                "bonus_points":["监控指标"],"critical_errors":["将缓存击穿等同于雪崩"]}
                """);
        when(gateway.chatJson(eq(Purpose.JUDGE), anyList(), anyString())).thenReturn("""
                {"score":8,"strengths":["能说明互斥重建"],"missing_points":["补充监控指标"],
                "confidence":0.85,"evidence_quotes":["缓存"]}
                """);
        PlanService plans = mock(PlanService.class);
        reports = new InterviewReportService(jdbc, new InterviewLlmService(gateway), plans);
        interviews = new InterviewSession(jdbc, new InterviewLlmService(gateway), new InterviewSessionRepository(jdbc), reports, plans);
    }

    @AfterAll
    void closeTestResources() {
        reports.shutdownCompletionExecutor();
        // JUnit normally stops @Container fields after @AfterAll. Stop it here
        // as well so the database connection is released before test teardown.
        postgres.stop();
    }

    @Test
    void persistsOwnerScopedCompletionFeedbackAndRetestLifecycle() {
        long alice = auth.register("interview-alice@example.com", "correct-horse", "Alice").userId();
        long bob = auth.register("interview-bob@example.com", "correct-horse", "Bob").userId();

        InterviewSession.InterviewMessage opened = interviews.open(alice, "后端开发", "需要 Java、Redis 和高并发经验",
                "technical", "MID", 15, "it-trace");
        assertThat(opened.sessionId()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT deadline_at IS NOT NULL FROM interview_sessions WHERE id=?", Boolean.class, opened.sessionId()))
                .isTrue();
        assertThatThrownBy(() -> interviews.session(bob, opened.sessionId()))
                .isInstanceOf(ResponseStatusException.class);

        InterviewSession.InterviewMessage first = interviews.answer(alice, opened.sessionId(), "用互斥锁避免热点缓存 key 同时重建。",
                "answer-1", "it-trace");
        assertThat(first.status()).isEqualTo("IN_PROGRESS");
        InterviewSession.InterviewMessage completed = interviews.answer(alice, opened.sessionId(), "缓存还会设置合理过期并监控命中率。",
                "answer-2", "it-trace");
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM interview_completion_jobs WHERE session_id=?", String.class, opened.sessionId()))
                .isEqualTo("queued");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_completion_jobs WHERE session_id=?", Long.class, opened.sessionId()))
                .isEqualTo(1L);

        interviews.feedback(alice, opened.sessionId(), "inaccurate", "漏掉了我的监控实践");
        AdminService admins = mock(AdminService.class);
        when(admins.requireAdmin()).thenReturn(alice, bob, alice, alice, bob, alice);
        InterviewScoreAnnotationService annotations = new InterviewScoreAnnotationService(jdbc, admins);
        List<Map<String, Object>> firstQueue = annotations.queue(10, 2);
        assertThat(firstQueue).hasSize(1).anySatisfy(item -> {
            assertThat(item).containsKey("questionId");
            assertThat(item).containsKey("answer");
            assertThat(item).containsEntry("feedbackRating", "inaccurate");
            assertThat(item).containsEntry("priority", 0);
            assertThat(item).doesNotContainKeys("userId", "sessionId");
        });
        Long questionId = ((Number) firstQueue.getFirst().get("questionId")).longValue();
        assertThat(firstQueue).anySatisfy(item -> {
            assertThat(item).containsEntry("questionId", questionId);
        });
        assertThat(annotations.queue(10, 2, true)).hasSize(1).anySatisfy(item -> {
            assertThat(item).containsEntry("questionId", questionId)
                    .containsEntry("modelScore", null)
                    .containsEntry("modelConfidence", null)
                    .containsEntry("feedbackRating", null)
                    .containsEntry("priority", null);
        });
        annotations.upsert(questionId, 8, "覆盖了核心机制");
        assertThat(annotations.queue(10, 2)).noneMatch(item -> questionId.equals(item.get("questionId")));
        annotations.upsert(questionId, 7, "边界条件还可以更完整");
        InterviewScoreEvalService.ReplayRequest replay = annotations.exportReplay("human-gold-it", 2);
        assertThat(replay.cases()).hasSize(1).first().extracting(InterviewScoreEvalService.ReplayCase::humanScore).isEqualTo(8);
        Map<String, Object> persisted = new InterviewScoreEvalService(new ObjectMapper(), jdbc).replay(replay);
        assertThat(persisted).containsKey("runId");

        InterviewSession.Report report = interviews.report(alice, opened.sessionId());
        assertThat(report.totalQuestions()).isEqualTo(2);
        assertThat(report.avgScore()).isEqualTo(8D);
        assertThat(report.scoreConfidence()).isEqualTo(0.85D);

        assertThat(jdbc.queryForObject("SELECT rating FROM interview_feedback WHERE session_id=?", String.class, opened.sessionId()))
                .isEqualTo("inaccurate");

        InterviewSession.InterviewMessage retest = interviews.retest(alice, opened.sessionId(), "it-trace");
        assertThat(jdbc.queryForObject("SELECT retest_of FROM interview_sessions WHERE id=?", String.class, retest.sessionId()))
                .isEqualTo(opened.sessionId());
        assertThat(interviews.history(bob, 10)).isEmpty();
        assertThat(interviews.history(alice, 10)).extracting(InterviewSession.HistoryItem::sessionId)
                .contains(retest.sessionId(), opened.sessionId());
    }

    @Test
    void retriesOnlyTheOwnersFailedDurableTurnWithoutDuplicatingItsAnswer() {
        long alice = auth.register("turn-alice@example.com", "correct-horse", "Alice").userId();
        long bob = auth.register("turn-bob@example.com", "correct-horse", "Bob").userId();
        String sessionId = interviews.open(alice, "后端开发", null, "technical", "MID", 15, "it-trace").sessionId();
        String jobId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO interview_turn_jobs (id, user_id, session_id, question_sequence, request_id, answer, trace_id,
                  status, attempts, finished_at)
                VALUES (?, ?, ?, 1, 'failed-request', '保留的回答', 'it-trace', 'FAILED', 3, now())
                """, jobId, alice, sessionId);
        InterviewTurnService turns = new InterviewTurnService(jdbc, interviews, new SimpleMeterRegistry());

        assertThatThrownBy(() -> turns.retry(bob, sessionId, jobId))
                .isInstanceOf(ResponseStatusException.class);

        InterviewTurnService.TurnJob retried = turns.retry(alice, sessionId, jobId);
        assertThat(retried.status()).isEqualTo("PENDING");
        assertThat(retried.attempts()).isZero();
        assertThat(jdbc.queryForObject("SELECT answer FROM interview_turn_jobs WHERE id=?", String.class, jobId))
                .isEqualTo("保留的回答");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_turn_jobs WHERE session_id=?", Long.class, sessionId))
                .isEqualTo(1L);
        assertThatThrownBy(() -> turns.retry(alice, sessionId, jobId))
                .isInstanceOf(ResponseStatusException.class);
    }
}
