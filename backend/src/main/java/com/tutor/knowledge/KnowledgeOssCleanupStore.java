package com.tutor.knowledge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** OSS 删除的持久化补偿队列；失败状态可跨进程重启保留。 */
@Component
public class KnowledgeOssCleanupStore {
    private final JdbcTemplate jdbc;
    private final OssStorage oss;
    private final TransactionTemplate transactions;

    public KnowledgeOssCleanupStore(JdbcTemplate jdbc, OssStorage oss, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.oss = oss;
        this.transactions = transactions;
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
        var job = transactions.execute(status -> {
            var rows = jdbc.query("""
                SELECT id, object_key, attempts FROM knowledge_oss_cleanup_jobs
                WHERE status IN ('pending','retryable_failed') AND next_attempt_at <= now()
                ORDER BY created_at LIMIT 1
                FOR UPDATE SKIP LOCKED
                """, (rs, i) -> new Job(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3)));
            if (rows.isEmpty()) return null;
            Job selected = rows.getFirst();
            jdbc.update("UPDATE knowledge_oss_cleanup_jobs SET status='processing', attempts=attempts+1, lease_until=now()+interval '10 minutes' WHERE id=? AND status IN ('pending','retryable_failed')", selected.id());
            return selected;
        });
        if (job == null) return;
        try {
            oss.delete(job.objectKey());
            jdbc.update("UPDATE knowledge_oss_cleanup_jobs SET status='completed', finished_at=now(), lease_until=NULL, last_error=NULL WHERE id=? AND status='processing'", job.id());
        } catch (RuntimeException error) {
            jdbc.update("UPDATE knowledge_oss_cleanup_jobs SET status=CASE WHEN attempts>=8 THEN 'failed' ELSE 'retryable_failed' END, lease_until=NULL, next_attempt_at=now() + (LEAST(attempts, 8) * interval '1 minute'), last_error=? WHERE id=? AND status='processing'",
                    compact(error.getMessage()), job.id());
        }
    }

    private record Job(UUID id, String objectKey, int attempts) {}
    private static String compact(String value) { return value == null ? "OSS 删除失败" : value.substring(0, Math.min(500, value.length())); }
}
