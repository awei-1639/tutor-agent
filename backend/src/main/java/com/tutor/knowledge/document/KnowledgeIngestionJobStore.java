package com.tutor.knowledge.document;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

/** 持久化租约队列：可安全跨重启和多应用实例使用。 */
@Component
public class KnowledgeIngestionJobStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final int leaseSeconds;

    public KnowledgeIngestionJobStore(JdbcTemplate jdbc, TransactionTemplate transactions,
                                      @Value("${knowledge.ingestion.lease-seconds:1800}") int leaseSeconds) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.leaseSeconds = Math.max(60, leaseSeconds);
    }

    public record Job(UUID id, UUID documentId, long documentGeneration, int attempts, UUID leaseToken) {}

    public UUID enqueue(UUID documentId, long generation) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO knowledge_ingestion_jobs (id, document_id, document_generation) VALUES (?,?,?)",
                id, documentId, generation);
        return id;
    }

    public int pendingCount() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM knowledge_ingestion_jobs WHERE status IN ('pending','retryable_failed','processing')", Integer.class);
        return count == null ? 0 : count;
    }

    public Optional<Job> claimNext() {
        return Optional.ofNullable(transactions.execute(status -> {
            var jobs = jdbc.query("""
                    SELECT id, document_id, document_generation, attempts
                    FROM knowledge_ingestion_jobs
                    WHERE (status IN ('pending','retryable_failed') AND next_attempt_at <= now()
                           OR status='processing' AND lease_until < now())
                      AND (lease_until IS NULL OR lease_until < now())
                    ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
                    """, (rs, i) -> new Job(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                    rs.getLong(3), rs.getInt(4), null));
            if (jobs.isEmpty()) return (Job) null;
            Job job = jobs.getFirst();
            UUID token = UUID.randomUUID();
            jdbc.update("""
                    UPDATE knowledge_ingestion_jobs
                    SET status='processing', stage='validating', attempts=attempts+1,
                        started_at=COALESCE(started_at, now()), lease_until=now() + (? * interval '1 second'), lease_token=?
                    WHERE id=?
                    """, leaseSeconds, token, job.id());
            return new Job(job.id(), job.documentId(), job.documentGeneration(), job.attempts() + 1, token);
        }));
    }

    public boolean stage(Job job, String stage) {
        return jdbc.update("UPDATE knowledge_ingestion_jobs SET stage=?, lease_until=now() + (? * interval '1 second') WHERE id=? AND lease_token=? AND status='processing' AND lease_until > now()",
                stage, leaseSeconds, job.id(), job.leaseToken()) == 1;
    }

    public boolean heartbeat(Job job) {
        return jdbc.update("UPDATE knowledge_ingestion_jobs SET lease_until=now() + (? * interval '1 second') WHERE id=? AND lease_token=? AND status='processing' AND lease_until > now()",
                leaseSeconds, job.id(), job.leaseToken()) == 1;
    }

    public boolean fence(Job job) {
        return jdbc.update("UPDATE knowledge_ingestion_jobs SET lease_until=now() + (? * interval '1 second') WHERE id=? AND lease_token=? AND status='processing' AND lease_until > now()",
                leaseSeconds, job.id(), job.leaseToken()) == 1;
    }

    public boolean complete(Job job) {
        return jdbc.update("UPDATE knowledge_ingestion_jobs SET status='completed', stage='completed', lease_until=NULL, finished_at=now() WHERE id=? AND lease_token=? AND status='processing' AND lease_until > now()",
                job.id(), job.leaseToken()) == 1;
    }

    /** 仅当当前围栏 worker 已持久化记录终态失败时返回 true。 */
    public boolean failFenced(Job job, String error) {
        boolean retry = job.attempts() < 5;
        int updated = jdbc.update("""
                UPDATE knowledge_ingestion_jobs
                SET status=?, stage='failed', lease_until=NULL, error_message=?,
                    next_attempt_at=CASE WHEN ? THEN now() + (? * interval '15 seconds') ELSE next_attempt_at END,
                    finished_at=CASE WHEN ? THEN NULL ELSE now() END
                WHERE id=? AND lease_token=? AND status='processing'
                """, retry ? "retryable_failed" : "failed", compact(error), retry,
                Math.min(job.attempts(), 12), retry, job.id(), job.leaseToken());
        return updated == 1 && !retry;
    }

    public void markDocumentFailed(UUID documentId, long generation, String error) {
        jdbc.update("""
                UPDATE knowledge_documents SET status='failed', error_message=?, updated_at=now()
                WHERE id=? AND generation=? AND deleted_at IS NULL
                """, compact(error), documentId, generation);
    }

    public void cancelForDocument(UUID documentId) {
        jdbc.update("UPDATE knowledge_ingestion_jobs SET status='cancelled', stage='cancelled', lease_until=NULL, finished_at=now() " +
                "WHERE document_id=? AND status IN ('pending','retryable_failed','processing')", documentId);
    }

    private static String compact(String error) {
        if (error == null || error.isBlank()) return "文档摄取失败";
        return error.substring(0, Math.min(error.length(), 500));
    }
}
