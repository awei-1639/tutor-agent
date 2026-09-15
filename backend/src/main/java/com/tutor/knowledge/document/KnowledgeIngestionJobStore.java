package com.tutor.knowledge.document;

import com.tutor.platform.jobs.LeasedJobQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** 持久化租约队列：可安全跨重启和多应用实例使用。 */
@Component
public class KnowledgeIngestionJobStore {
    private static final int MAX_ATTEMPTS = 5;
    private static final LeasedJobQueue.LeaseTable TABLE = LeasedJobQueue.LeaseTable.of(
            "knowledge_ingestion_jobs", "processing", "completed", "id=?");
    /** pending/retryable 到期可领取；崩溃 worker 留下的过期 processing 行也可被接管（有上限）。 */
    private static final String CLAIM_WHERE =
            "(status IN ('pending','retryable_failed') AND next_attempt_at <= now())"
                    + " OR (status='processing' AND lease_until < now() AND attempts < " + MAX_ATTEMPTS + ")";

    private final JdbcTemplate jdbc;
    private final LeasedJobQueue queue;
    private final int leaseSeconds;

    public KnowledgeIngestionJobStore(JdbcTemplate jdbc, LeasedJobQueue queue,
                                      @Value("${knowledge.ingestion.lease-seconds:1800}") int leaseSeconds) {
        this.jdbc = jdbc;
        this.queue = queue;
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
        return queue.claimNext(TABLE, CLAIM_WHERE,
                "status='processing', stage='validating', attempts=j.attempts+1,"
                        + " started_at=COALESCE(j.started_at, now()),"
                        + " lease_token=?, lease_until=now() + (? * interval '1 second')",
                "created_at",
                "j.id, j.document_id, j.document_generation, j.attempts, j.lease_token",
                (rs, i) -> new Job(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getLong(3), rs.getInt(4), rs.getObject(5, UUID.class)),
                UUID.randomUUID(), (long) leaseSeconds);
    }

    /** 把崩溃 worker 遗留、租约耗尽且超过重试上限的任务批量置为 failed（死信）。 */
    public int sweepExhausted() {
        return queue.expireExhausted(TABLE,
                "status='failed', stage='failed', error_message=?, lease_until=NULL, finished_at=now()",
                new Object[]{"任务租约已耗尽"}, "attempts >= ?", MAX_ATTEMPTS);
    }

    /** 阶段推进并顺带续租。 */
    public boolean stage(Job job, String stage) {
        return queue.fencedUpdate(TABLE, job.id(), job.leaseToken(),
                "stage=?, lease_until=now() + (? * interval '1 second')", stage, (long) leaseSeconds);
    }

    public boolean heartbeat(Job job) {
        return queue.renew(TABLE, job.id(), job.leaseToken(), leaseSeconds);
    }

    public boolean fence(Job job) {
        return queue.renew(TABLE, job.id(), job.leaseToken(), leaseSeconds);
    }

    public boolean complete(Job job) {
        return queue.complete(TABLE, job.id(), job.leaseToken(), ", stage='completed', finished_at=now()");
    }

    /** 仅当当前围栏 worker 已持久化记录终态失败时返回 true。 */
    public boolean failFenced(Job job, String error) {
        boolean retry = job.attempts() < MAX_ATTEMPTS;
        boolean fenced = queue.fencedUpdate(TABLE, job.id(), job.leaseToken(), """
                status=?, stage='failed', lease_until=NULL, error_message=?,
                    next_attempt_at=CASE WHEN ? THEN now() + (? * interval '15 seconds') ELSE next_attempt_at END,
                    finished_at=CASE WHEN ? THEN NULL ELSE now() END""",
                retry ? "retryable_failed" : "failed", compact(error), retry,
                (long) Math.min(job.attempts(), 12), retry);
        return fenced && !retry;
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
