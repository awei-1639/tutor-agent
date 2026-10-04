package com.tutor.knowledge.document;

import com.tutor.platform.jobs.LeasedJobQueue;
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

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * knowledge_ingestion_jobs 的崩溃路径回归：摄取是长流程（解析/分块/向量化多阶段），
 * 崩溃残留会卡住用户的文档进度页。锁定：5 次重试上限、死信清扫、围栏内阶段推进/心跳、
 * 围栏失效后的迟到写、按文档取消。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "runIntegrationTests", matches = "true")
class KnowledgeIngestionJobsPostgresIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private KnowledgeIngestionJobStore store;

    @BeforeAll
    void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new KnowledgeIngestionJobStore(jdbc, new LeasedJobQueue(jdbc), 60);
    }

    @BeforeEach
    void clean() {
        jdbc.update("""
                TRUNCATE knowledge_ingestion_jobs, knowledge_document_chunk_staging,
                         knowledge_documents, users RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void crashRetakesExpireAtCapAndSweepToDeadLetter() {
        UUID documentId = insertDocument();
        UUID jobId = store.enqueue(documentId, 0L);

        for (int taken = 1; taken <= 5; taken++) {
            Optional<KnowledgeIngestionJobStore.Job> job = store.claimNext();
            assertThat(job).as("第 %d 次领取", taken).isPresent();
            assertThat(job.get().id()).isEqualTo(jobId);
            assertThat(job.get().attempts()).isEqualTo(taken);
            jdbc.update("UPDATE knowledge_ingestion_jobs SET lease_until=now() - interval '1 second' WHERE id=?", jobId);
        }
        assertThat(store.claimNext()).isEmpty();

        assertThat(store.sweepExhausted()).isEqualTo(1);
        Map<String, Object> dead = jdbc.queryForMap(
                "SELECT status, stage, error_message FROM knowledge_ingestion_jobs WHERE id=?", jobId);
        assertThat(dead.get("status")).isEqualTo("failed");
        assertThat(dead.get("stage")).isEqualTo("failed");
        assertThat(dead.get("error_message")).isEqualTo("任务租约已耗尽");
        assertThat(store.sweepExhausted()).isZero();
    }

    @Test
    void fencedStageHeartbeatAndStaleTokenWrites() {
        UUID documentId = insertDocument();
        UUID jobId = store.enqueue(documentId, 0L);

        KnowledgeIngestionJobStore.Job job = store.claimNext().orElseThrow();
        assertThat(store.stage(job, "parsing")).isTrue();
        assertThat(store.heartbeat(job)).isTrue();
        assertThat(store.complete(job)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM knowledge_ingestion_jobs WHERE id=?",
                String.class, jobId)).isEqualTo("completed");

        // 围栏失效：接管者领取后, 旧 token 的任何围栏内写都必须失败。
        UUID secondJob = store.enqueue(documentId, 1L);
        KnowledgeIngestionJobStore.Job stale = store.claimNext().orElseThrow();
        assertThat(stale.id()).isEqualTo(secondJob);
        jdbc.update("UPDATE knowledge_ingestion_jobs SET lease_until=now() - interval '1 second' WHERE id=?", secondJob);
        KnowledgeIngestionJobStore.Job fresh = store.claimNext().orElseThrow();
        assertThat(fresh.leaseToken()).isNotEqualTo(stale.leaseToken());
        assertThat(store.stage(stale, "chunking")).isFalse();
        assertThat(store.complete(stale)).isFalse();
        assertThat(store.failFenced(stale, "late failure")).isFalse();
        assertThat(store.complete(fresh)).isTrue();
    }

    @Test
    void cancelForDocumentKillsAllPendingJobs() {
        UUID documentId = insertDocument();
        store.enqueue(documentId, 0L);
        store.enqueue(documentId, 1L);

        store.cancelForDocument(documentId);

        Integer cancelled = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_ingestion_jobs WHERE document_id=? AND status='cancelled'",
                Integer.class, documentId);
        assertThat(cancelled).isEqualTo(2);
        assertThat(store.claimNext()).isEmpty();
    }

    private UUID insertDocument() {
        long userId = jdbc.queryForObject(
                "INSERT INTO users(email, password_hash, name) VALUES (?, ?, ?) RETURNING id",
                Long.class, "ingest-it-" + UUID.randomUUID() + "@example.com", "hash", "Ingest IT");
        UUID documentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO knowledge_documents (id, title, original_filename, content_type, size_bytes,
                                                 content_hash, oss_object_key, created_by)
                VALUES (?, '架构文档', 'arch.pdf', 'application/pdf', 1024, ?, ?, ?)
                """, documentId, "hash-" + documentId, "it/" + documentId + "/arch.pdf", userId);
        return documentId;
    }
}
