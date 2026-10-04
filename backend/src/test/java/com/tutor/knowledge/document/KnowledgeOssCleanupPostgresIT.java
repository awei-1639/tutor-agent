package com.tutor.knowledge.document;

import com.tutor.platform.config.OssProperties;
import com.tutor.platform.jobs.LeasedJobQueue;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * knowledge_oss_cleanup_jobs 的崩溃路径回归。OSS 删除是补偿队列：
 * 删除失败会静默泄漏存储对象, 崩溃残留会永久卡死, 因此锁定
 * 成功完成、退避重试、重试上限死信、租约耗尽清扫、过期租约接管五条路径。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "runIntegrationTests", matches = "true")
class KnowledgeOssCleanupPostgresIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private FakeOssStorage oss;
    private KnowledgeOssCleanupStore store;

    /** 只覆写 delete 的假 OSS; 懒构造的 client 永远不会被触碰。 */
    static class FakeOssStorage extends OssStorage {
        final List<String> deleted = new ArrayList<>();
        boolean failDeletes;

        FakeOssStorage() {
            super(new OssProperties(false, null, null, null, null, null, null));
        }

        @Override
        public void delete(String objectKey) {
            if (failDeletes) throw new IllegalStateException("模拟 OSS 不可用");
            deleted.add(objectKey);
        }
    }

    @BeforeAll
    void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        oss = new FakeOssStorage();
        store = new KnowledgeOssCleanupStore(jdbc, oss,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new LeasedJobQueue(jdbc));
    }

    @BeforeEach
    void clean() {
        oss.deleted.clear();
        oss.failDeletes = false;
        jdbc.update("TRUNCATE knowledge_oss_cleanup_jobs");
    }

    @Test
    void completesDeletionAndMarksJobCompleted() {
        store.enqueue("it/object-a.pdf", "document-deleted");

        store.processOne();

        assertThat(oss.deleted).containsExactly("it/object-a.pdf");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, last_error FROM knowledge_oss_cleanup_jobs WHERE object_key='it/object-a.pdf'");
        assertThat(row.get("status")).isEqualTo("completed");
        assertThat(row.get("last_error")).isNull();
    }

    @Test
    void ossFailureBacksOffRetriesAndEventuallyDeadLetters() {
        store.enqueue("it/object-b.pdf", "document-deleted");
        oss.failDeletes = true;

        // 8 次失败 = MAX_ATTEMPTS: 前几次 retryable_failed + 分钟级退避, 达上限直接 failed。
        for (int attempt = 1; attempt <= 8; attempt++) {
            store.processOne();
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT status, attempts FROM knowledge_oss_cleanup_jobs WHERE object_key='it/object-b.pdf'");
            if (attempt < 8) {
                assertThat(row.get("status")).isEqualTo("retryable_failed");
            } else {
                assertThat(row.get("status")).isEqualTo("failed");
                assertThat((Integer) row.get("attempts")).isEqualTo(8);
            }
            if (attempt < 8) {
                // 拨回退避时间, 模拟退避窗口流逝。
                jdbc.update("UPDATE knowledge_oss_cleanup_jobs SET next_attempt_at=now() - interval '1 second' "
                        + "WHERE object_key='it/object-b.pdf'");
            }
        }
        assertThat(oss.deleted).isEmpty();
        String lastError = jdbc.queryForObject(
                "SELECT last_error FROM knowledge_oss_cleanup_jobs WHERE object_key='it/object-b.pdf'", String.class);
        assertThat(lastError).contains("模拟 OSS 不可用");
    }

    @Test
    void retakesExpiredProcessingLeaseWithNewFencingToken() {
        store.enqueue("it/object-c.pdf", "document-deleted");
        // 模拟 worker 领取后崩溃: 行停在 processing, 租约已过期, attempts=1。
        jdbc.update("""
                UPDATE knowledge_oss_cleanup_jobs
                SET status='processing', attempts=1,
                    lease_token=gen_random_uuid(), lease_until=now() - interval '1 second'
                WHERE object_key='it/object-c.pdf'
                """);

        store.processOne();

        assertThat(oss.deleted).containsExactly("it/object-c.pdf");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, attempts FROM knowledge_oss_cleanup_jobs WHERE object_key='it/object-c.pdf'");
        assertThat(row.get("status")).isEqualTo("completed");
        assertThat((Integer) row.get("attempts")).isEqualTo(2);
    }

    @Test
    void sweepsExhaustedCrashResidueToDeadLetter() {
        store.enqueue("it/object-d.pdf", "document-deleted");
        // 崩溃残留: attempts 已达上限 8 且租约过期, 若无清扫将永久卡死并泄漏 OSS 对象。
        jdbc.update("""
                UPDATE knowledge_oss_cleanup_jobs
                SET status='processing', attempts=8,
                    lease_token=gen_random_uuid(), lease_until=now() - interval '1 second'
                WHERE object_key='it/object-d.pdf'
                """);

        store.processOne();

        assertThat(oss.deleted).isEmpty();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, last_error, finished_at FROM knowledge_oss_cleanup_jobs WHERE object_key='it/object-d.pdf'");
        assertThat(row.get("status")).isEqualTo("failed");
        assertThat(row.get("last_error")).isEqualTo("任务租约已耗尽");
        assertThat(row.get("finished_at")).isNotNull();
    }
}
