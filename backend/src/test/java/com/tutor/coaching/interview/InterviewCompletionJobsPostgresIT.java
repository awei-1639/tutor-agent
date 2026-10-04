package com.tutor.coaching.interview;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * interview_completion_jobs 的崩溃路径回归。该表在 #31 修复前有 P0 缺陷：
 * 崩溃残留（attempts=3 + running）永远无人清扫，面试闭环永久卡死。
 * 这里用真实 PostgreSQL 锁定：重试上限、死信清扫、围栏失效、按 session 幂等入队。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "runIntegrationTests", matches = "true")
class InterviewCompletionJobsPostgresIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private InterviewCompletionJobStore store;

    @BeforeAll
    void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new InterviewCompletionJobStore(jdbc, new com.tutor.platform.jobs.LeasedJobQueue(jdbc));
    }

    @BeforeEach
    void clean() {
        jdbc.update("TRUNCATE interview_completion_jobs, interview_sessions, users RESTART IDENTITY CASCADE");
    }

    @Test
    void crashRetakesExpireAtCapAndSweepToDeadLetter() {
        long userId = insertUser();
        String sessionId = insertSession(userId);
        store.enqueue(userId, sessionId);

        for (int taken = 1; taken <= 3; taken++) {
            Optional<InterviewCompletionJobStore.Job> job = store.claimNext();
            assertThat(job).as("第 %d 次领取", taken).isPresent();
            assertThat(job.get().sessionId()).isEqualTo(sessionId);
            jdbc.update("UPDATE interview_completion_jobs SET lease_until=now() - interval '1 second' WHERE id=?",
                    job.get().id());
        }
        // 上限耗尽后不再可领取; 不清扫的话该 session 的闭环永远卡在 running。
        assertThat(store.claimNext()).isEmpty();

        assertThat(store.sweepExhausted()).isEqualTo(1);
        InterviewCompletionJobStore.Status dead = store.status(userId, sessionId).orElseThrow();
        assertThat(dead.status()).isEqualTo("failed");
        assertThat(dead.lastError()).isEqualTo("任务租约已耗尽");
        assertThat(dead.finishedAt()).isNotNull();
        // 死信行不再被清扫二次修改。
        assertThat(store.sweepExhausted()).isZero();
    }

    @Test
    void enqueueIsIdempotentPerSessionAndStaleWorkerCannotComplete() {
        long userId = insertUser();
        String sessionId = insertSession(userId);
        store.enqueue(userId, sessionId);

        InterviewCompletionJobStore.Job first = store.claimNext().orElseThrow();
        jdbc.update("UPDATE interview_completion_jobs SET lease_until=now() - interval '1 second' WHERE id=?",
                first.id());
        // UNIQUE(session_id) + ON CONFLICT DO NOTHING: 重复入队不会重置/复制任务。
        store.enqueue(userId, sessionId);

        InterviewCompletionJobStore.Job second = store.claimNext().orElseThrow();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(store.markCompleted(first)).isFalse();
        assertThat(store.markCompleted(second)).isTrue();

        InterviewCompletionJobStore.Status done = store.status(userId, sessionId).orElseThrow();
        assertThat(done.status()).isEqualTo("completed");
        assertThat(done.evidenceStatus()).isEqualTo("completed");
        assertThat(done.learningPlanStatus()).isEqualTo("completed");
    }

    @Test
    void fencedFailureBelowCapRequeuesForRetry() {
        long userId = insertUser();
        String sessionId = insertSession(userId);
        store.enqueue(userId, sessionId);

        InterviewCompletionJobStore.Job job = store.claimNext().orElseThrow();
        store.markFailure(job, new IllegalStateException("LLM 报告生成超时"));

        InterviewCompletionJobStore.Status retried = store.status(userId, sessionId).orElseThrow();
        assertThat(retried.status()).isEqualTo("queued");
        assertThat(retried.lastError()).isEqualTo("LLM 报告生成超时");
        // 回到队列后可重新领取 (失败未达上限, 不进死信)。
        assertThat(store.claimNext()).isPresent();
    }

    private long insertUser() {
        return jdbc.queryForObject(
                "INSERT INTO users(email, password_hash, name) VALUES (?, ?, ?) RETURNING id",
                Long.class, "completion-it-" + UUID.randomUUID() + "@example.com", "hash", "Completion IT");
    }

    private String insertSession(long userId) {
        return jdbc.queryForObject("""
                INSERT INTO interview_sessions (id, user_id, topic, status, current_question_sequence, deadline_at)
                VALUES (?, ?, '系统设计', 'COMPLETED', 5, now() + interval '1 day') RETURNING id
                """, String.class, UUID.randomUUID().toString(), userId);
    }
}
