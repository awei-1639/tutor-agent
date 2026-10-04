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
 * interview_turn_jobs 的崩溃路径回归：租约过期被接管、重试上限封顶、死信清扫、
 * 围栏失效后的迟到完成、可重试失败的退避。P0-1 同类缺陷（崩溃残留卡死）在此表同样致命：
 * 用户的一个回答会被永久卡在 PROCESSING，面试会话无法推进。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "runIntegrationTests", matches = "true")
class InterviewTurnJobsPostgresIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private InterviewTurnJobStore store;

    @BeforeAll
    void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new InterviewTurnJobStore(jdbc, new com.tutor.platform.jobs.LeasedJobQueue(jdbc));
    }

    @BeforeEach
    void clean() {
        jdbc.update("TRUNCATE interview_turn_jobs, interview_sessions, users RESTART IDENTITY CASCADE");
    }

    @Test
    void crashRetakesExpireAtCapAndSweepToDeadLetter() {
        long userId = insertUser();
        String sessionId = insertSession(userId);
        String jobId = UUID.randomUUID().toString();
        store.insert(jobId, userId, sessionId, 1, "req-crash", "answer", "trace-crash");

        // 模拟 worker 反复崩溃：领取后不完成，把租约拨回过去供下次接管。
        for (int taken = 1; taken <= 3; taken++) {
            Optional<InterviewTurnJobStore.ClaimedJob> job = store.claimNext();
            assertThat(job).as("第 %d 次领取", taken).isPresent();
            assertThat(job.get().id()).isEqualTo(jobId);
            assertThat(job.get().attempts()).isEqualTo(taken);
            jdbc.update("UPDATE interview_turn_jobs SET lease_until=now() - interval '1 second' WHERE id=?", jobId);
        }
        // 重试上限耗尽后不再可领取（避免崩溃循环无限重跑 LLM）。
        assertThat(store.claimNext()).isEmpty();

        // 死信清扫把卡死的 PROCESSING 行置为 FAILED。
        assertThat(store.sweepAbandoned()).isEqualTo(1);
        InterviewTurnService.TurnJob dead = store.find(userId, sessionId, jobId).orElseThrow();
        assertThat(dead.status()).isEqualTo("FAILED");
        assertThat(dead.lastError()).isEqualTo("任务租约已耗尽");
        assertThat(dead.finishedAt()).isNotNull();
    }

    @Test
    void staleWorkerCannotCompleteAfterLeaseTakeover() {
        long userId = insertUser();
        String sessionId = insertSession(userId);
        String jobId = UUID.randomUUID().toString();
        store.insert(jobId, userId, sessionId, 1, "req-fence", "answer", "trace-fence");

        InterviewTurnJobStore.ClaimedJob first = store.claimNext().orElseThrow();
        jdbc.update("UPDATE interview_turn_jobs SET lease_until=now() - interval '1 second' WHERE id=?", jobId);
        InterviewTurnJobStore.ClaimedJob second = store.claimNext().orElseThrow();

        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
        // 旧 worker 的迟到完成必须被围栏挡下, 否则同一回答会被写两次。
        assertThat(store.complete(first, "OK", "late answer")).isFalse();
        assertThat(store.complete(second, "OK", "fresh answer")).isTrue();

        InterviewTurnService.TurnJob done = store.find(userId, sessionId, jobId).orElseThrow();
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.responseMessage()).isEqualTo("fresh answer");
    }

    @Test
    void retryableFailureBacksOffThenRetakesWithFencing() {
        long userId = insertUser();
        String sessionId = insertSession(userId);
        String jobId = UUID.randomUUID().toString();
        store.insert(jobId, userId, sessionId, 1, "req-retry", "answer", "trace-retry");

        InterviewTurnJobStore.ClaimedJob job = store.claimNext().orElseThrow();
        assertThat(store.fail(job, "RETRYABLE_FAILED", "LLM 评分超时", true)).isTrue();

        InterviewTurnService.TurnJob failed = store.find(userId, sessionId, jobId).orElseThrow();
        assertThat(failed.status()).isEqualTo("RETRYABLE_FAILED");
        assertThat(failed.lastError()).isEqualTo("LLM 评分超时");
        // 退避期内不可领取（next_attempt_at = now + 5s）。
        assertThat(store.claimNext()).isEmpty();

        // 拨回退避时间后重新领取, attempts 递增且换发新围栏。
        jdbc.update("UPDATE interview_turn_jobs SET next_attempt_at=now() - interval '1 second' WHERE id=?", jobId);
        InterviewTurnJobStore.ClaimedJob retaken = store.claimNext().orElseThrow();
        assertThat(retaken.attempts()).isEqualTo(2);
        assertThat(retaken.leaseToken()).isNotEqualTo(job.leaseToken());
    }

    private long insertUser() {
        return jdbc.queryForObject(
                "INSERT INTO users(email, password_hash, name) VALUES (?, ?, ?) RETURNING id",
                Long.class, "turn-it-" + UUID.randomUUID() + "@example.com", "hash", "Turn IT");
    }

    private String insertSession(long userId) {
        return jdbc.queryForObject("""
                INSERT INTO interview_sessions (id, user_id, topic, status, current_question_sequence, deadline_at)
                VALUES (?, ?, 'Java 后端', 'IN_PROGRESS', 1, now() + interval '1 day') RETURNING id
                """, String.class, UUID.randomUUID().toString(), userId);
    }
}
