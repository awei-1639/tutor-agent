package com.tutor.memory.external;

import com.tutor.memory.policy.MemoryAdmissionPolicy;
import com.tutor.memory.policy.MemoryDeletionRateLimiter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 PostgreSQL 验证 V53/V54、租约 fencing 和单条删除 Outbox；仅在集成测试开关打开时运行。 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "runIntegrationTests", matches = "true")
class MemorySyncOutboxPostgresIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeAll
    void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void clean() {
        jdbc.update("TRUNCATE memory_sync_outbox, users RESTART IDENTITY CASCADE");
    }

    @Test
    void migratesRemoteIdAndLeaseColumns() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name='episodes' AND column_name='remote_memory_id'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name='memory_sync_outbox' AND column_name IN ('remote_memory_id', 'lease_token', 'lease_until')
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_name='memory_retry_rate_limits'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void claimsSingleDeleteWithNullRemoteIdAndFencesLateWorker() {
        long userId = insertUser();
        MemorySyncOutbox outbox = new MemorySyncOutbox(jdbc, transactions, new MemoryAdmissionPolicy(), 30);

        outbox.enqueueDeleteMemory(userId, 101L, null);
        MemorySyncOutbox.Job job = outbox.claimNext().orElseThrow();

        assertThat(job.remoteMemoryId()).isNull();
        assertThat(job.leaseToken()).isNotNull();
        assertThat(status(job.id())).isEqualTo("processing");

        outbox.complete(job.id(), UUID.randomUUID());
        assertThat(status(job.id())).isEqualTo("processing");

        outbox.complete(job.id(), job.leaseToken());
        assertThat(status(job.id())).isEqualTo("completed");
    }

    @Test
    void reclaimsExpiredLeaseWithNewFencingToken() {
        long userId = insertUser();
        MemorySyncOutbox outbox = new MemorySyncOutbox(jdbc, transactions, new MemoryAdmissionPolicy(), 30);

        outbox.enqueueDeleteMemory(userId, 202L, "remote-uuid");
        MemorySyncOutbox.Job first = outbox.claimNext().orElseThrow();
        jdbc.update("UPDATE memory_sync_outbox SET lease_until=now() WHERE id=?", first.id());

        MemorySyncOutbox.Job reclaimed = outbox.claimNext().orElseThrow();

        assertThat(reclaimed.id()).isEqualTo(first.id());
        assertThat(reclaimed.attemptCount()).isEqualTo(2);
        assertThat(reclaimed.leaseToken()).isNotEqualTo(first.leaseToken());
        outbox.complete(first.id(), first.leaseToken());
        assertThat(status(first.id())).isEqualTo("processing");
        outbox.complete(reclaimed.id(), reclaimed.leaseToken());
        assertThat(status(first.id())).isEqualTo("completed");
    }

    @Test
    void sharesRetryRateLimitAcrossCallsUsingPostgresRowLock() {
        long userId = insertUser();
        MemoryDeletionRateLimiter limiter = new MemoryDeletionRateLimiter(jdbc, 2);

        assertThat(limiter.tryAcquire(userId)).isTrue();
        assertThat(limiter.tryAcquire(userId)).isTrue();
        assertThat(limiter.tryAcquire(userId)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT request_count FROM memory_retry_rate_limits WHERE user_id=?", Integer.class, userId))
                .isEqualTo(3);
    }

    private long insertUser() {
        return jdbc.queryForObject("INSERT INTO users DEFAULT VALUES RETURNING id", Long.class);
    }

    private String status(long jobId) {
        return jdbc.queryForObject("SELECT status FROM memory_sync_outbox WHERE id=?", String.class, jobId);
    }
}
