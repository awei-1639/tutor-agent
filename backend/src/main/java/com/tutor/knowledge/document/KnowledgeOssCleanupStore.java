package com.tutor.knowledge.document;

import com.tutor.platform.jobs.LeasedJobQueue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** OSS 删除的持久化补偿队列；失败状态可跨进程重启保留。 */
@Component
public class KnowledgeOssCleanupStore {
    private static final long LEASE_SECONDS = 600;
    private static final int MAX_ATTEMPTS = 8;
    private static final LeasedJobQueue.LeaseTable TABLE =
            LeasedJobQueue.LeaseTable.of("knowledge_oss_cleanup_jobs", "processing", "completed", "id=?");
    /** pending/retryable 到期可领取；崩溃 worker 留下的过期 processing 行也可被接管。 */
    private static final String CLAIM_WHERE =
            "(status IN ('pending','retryable_failed') AND next_attempt_at <= now())"
                    + " OR (status='processing' AND lease_until < now() AND attempts < " + MAX_ATTEMPTS + ")";

    private final JdbcTemplate jdbc;
    private final OssStorage oss;
    private final TransactionTemplate transactions;
    private final LeasedJobQueue queue;

    public KnowledgeOssCleanupStore(JdbcTemplate jdbc, OssStorage oss, TransactionTemplate transactions,
                                    LeasedJobQueue queue) {
        this.jdbc = jdbc;
        this.oss = oss;
        this.transactions = transactions;
        this.queue = queue;
    }

    public void enqueue(String objectKey, String reason) {
        jdbc.update("""
                INSERT INTO knowledge_oss_cleanup_jobs (id, object_key, reason)
                VALUES (?, ?, ?)
                ON CONFLICT (object_key) DO UPDATE SET status='pending', next_attempt_at=now(), last_error=NULL, finished_at=NULL
                """, UUID.randomUUID(), objectKey, reason);
    }

    @Scheduled(fixedDelayString = "${knowledge.oss-cleanup.poll-ms:30000}")
    public void processOne() {
        // 先回收租约耗尽的死任务（此前过期的 processing 行会永久卡死，OSS 对象随之泄漏）。
        queue.expireExhausted(TABLE, "status='failed', last_error=?, finished_at=now()",
                new Object[]{"任务租约已耗尽"}, "attempts >= ?", MAX_ATTEMPTS);
        Job job = transactions.execute(status -> queue.claimNext(TABLE, CLAIM_WHERE,
                "status='processing', attempts=j.attempts+1, lease_token=?, lease_until=now() + (? * interval '1 second')",
                "created_at", "j.id, j.object_key, j.attempts, j.lease_token",
                (rs, i) -> new Job(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3),
                        rs.getObject(4, UUID.class)),
                UUID.randomUUID(), LEASE_SECONDS).orElse(null));
        if (job == null) return;
        try {
            oss.delete(job.objectKey());
            queue.complete(TABLE, job.id(), job.leaseToken(), ", finished_at=now(), last_error=NULL");
        } catch (RuntimeException error) {
            String targetStatus = job.attempts() >= MAX_ATTEMPTS ? "failed" : "retryable_failed";
            queue.fail(TABLE, job.id(), job.leaseToken(), targetStatus,
                    ", next_attempt_at=now() + (LEAST(attempts, 8) * interval '1 minute'), last_error=?",
                    compact(error.getMessage()));
        }
    }

    record Job(UUID id, String objectKey, int attempts, UUID leaseToken) {}
    private static String compact(String value) { return value == null ? "OSS 删除失败" : value.substring(0, Math.min(500, value.length())); }
}
