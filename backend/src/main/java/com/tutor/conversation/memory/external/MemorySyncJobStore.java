package com.tutor.conversation.memory.external;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable storage boundary for external-memory sync jobs and their leases. */
final class MemorySyncJobStore {
    private static final com.tutor.platform.jobs.LeasedJobQueue.LeaseTable TABLE =
            com.tutor.platform.jobs.LeasedJobQueue.LeaseTable.of(
                    "memory_sync_outbox", "processing", "completed", "id=?");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final com.tutor.platform.jobs.LeasedJobQueue queue;
    private final int leaseSeconds;

    MemorySyncJobStore(JdbcTemplate jdbc, TransactionTemplate transactions,
                       com.tutor.platform.jobs.LeasedJobQueue queue, int leaseSeconds) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.queue = queue;
        this.leaseSeconds = leaseSeconds;
    }

    /**
     * 领取走统一内核（单条 SKIP LOCKED CTE 原子领取）。complete/fail 刻意保留本地的
     * 宽松围栏（只校验 token、不校验 lease_until）：outbox 行是用户可见的删除进度状态，
     * 租约过期后迟到 worker 的远程操作结果仍需落库，否则远程状态与本地脱节；
     * 过期未完成的行由 claim 的接管分支回收。
     */
    Optional<MemorySyncOutbox.Job> claimNext() {
        return queue.claimNext(TABLE,
                "(status IN ('pending', 'retryable') AND next_attempt_at <= now())"
                        + " OR (status='processing' AND lease_until <= now())",
                "status='processing', attempt_count=attempt_count+1,"
                        + " lease_token=?, lease_until=now() + (? * interval '1 second')",
                "id",
                "j.id, j.user_id, j.memory_generation, j.operation, j.memory_id, j.remote_memory_id,"
                        + " j.summary, j.topics, j.open_items, j.attempt_count, j.lease_token",
                (rs, i) -> new MemorySyncOutbox.Job(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getString(4), rs.getObject(5, Long.class), rs.getString(6), rs.getString(7),
                        textArray(rs.getArray(8)), textArray(rs.getArray(9)), rs.getInt(10),
                        rs.getObject(11, UUID.class)),
                UUID.randomUUID(), (long) leaseSeconds);
    }

    void complete(long jobId) {
        jdbc.update("""
                UPDATE memory_sync_outbox
                SET status='completed', completed_at=now(), last_error=NULL,
                    lease_token=NULL, lease_until=NULL
                WHERE id=?
                """, jobId);
    }

    void complete(long jobId, UUID leaseToken) {
        if (leaseToken == null) {
            complete(jobId);
            return;
        }
        jdbc.update("""
                UPDATE memory_sync_outbox
                SET status='completed', completed_at=now(), last_error=NULL,
                    lease_token=NULL, lease_until=NULL
                WHERE id=? AND status='processing' AND lease_token=?
                """, jobId, leaseToken);
    }

    void fail(long jobId, int attemptCount, String error) {
        fail(jobId, null, attemptCount, error);
    }

    void fail(long jobId, UUID leaseToken, int attemptCount, String error) {
        boolean retryable = attemptCount < 5;
        if (leaseToken == null) {
            jdbc.update("""
                    UPDATE memory_sync_outbox
                    SET status=?, last_error=?,
                        next_attempt_at=CASE WHEN ? THEN now() + (? * interval '10 seconds') ELSE next_attempt_at END,
                        lease_token=NULL, lease_until=NULL
                    WHERE id=?
                    """, retryable ? "retryable" : "failed", compactError(error), retryable,
                    Math.min(attemptCount, 12), jobId);
            return;
        }
        jdbc.update("""
                UPDATE memory_sync_outbox
                SET status=?, last_error=?,
                    next_attempt_at=CASE WHEN ? THEN now() + (? * interval '10 seconds') ELSE next_attempt_at END,
                    lease_token=NULL, lease_until=NULL
                WHERE id=? AND status='processing' AND lease_token=?
                """, retryable ? "retryable" : "failed", compactError(error), retryable,
                Math.min(attemptCount, 12), jobId, leaseToken);
    }

    int requeueFailedForUser(long userId) {
        return transactions.execute(status -> jdbc.update("""
                UPDATE memory_sync_outbox o
                SET status='pending', attempt_count=0, next_attempt_at=now(),
                    last_error=NULL, completed_at=NULL, lease_token=NULL, lease_until=NULL
                WHERE o.user_id=?
                  AND o.status='failed'
                  AND o.memory_generation=(
                      SELECT u.memory_generation FROM users u WHERE u.id=?
                  )
                """, userId, userId));
    }

    boolean isGenerationCurrent(long userId, long generation) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE id=? AND memory_generation=?",
                Integer.class, userId, generation);
        return count != null && count > 0;
    }

    boolean isUpsertAllowed(long userId, long generation, long memoryId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*) FROM users u
                WHERE u.id=? AND u.memory_generation=? AND u.external_memory_enabled=true
                  AND EXISTS (
                      SELECT 1 FROM episodes e
                      WHERE e.id=? AND e.user_id=u.id AND e.status='active'
                        AND (e.expires_at IS NULL OR e.expires_at > now())
                  )
                """, Integer.class, userId, generation, memoryId);
        return count != null && count > 0;
    }

    MemorySyncOutbox.DeletionStatus latestDeletionStatus(long userId) {
        return jdbc.query("""
                SELECT status, attempt_count, last_error FROM memory_sync_outbox
                WHERE user_id=? AND operation IN ('delete_user', 'delete_memory')
                ORDER BY id DESC LIMIT 1
                """, (rs, i) -> new MemorySyncOutbox.DeletionStatus(rs.getString(1), rs.getInt(2), rs.getString(3)), userId)
                .stream().findFirst().orElse(new MemorySyncOutbox.DeletionStatus("not_requested", 0, null));
    }

    Optional<MemorySyncOutbox.DeletionStatus> latestDeletionStatus(long userId, long memoryId) {
        return jdbc.query("""
                SELECT status, attempt_count, last_error FROM memory_sync_outbox
                WHERE user_id=? AND memory_id=? AND operation='delete_memory'
                ORDER BY id DESC LIMIT 1
                """, (rs, i) -> new MemorySyncOutbox.DeletionStatus(rs.getString(1), rs.getInt(2), rs.getString(3)),
                userId, memoryId).stream().findFirst();
    }

    boolean remoteReadAllowed(long userId) {
        String status = jdbc.query("""
                SELECT status FROM memory_sync_outbox
                WHERE user_id=? AND operation='delete_user'
                ORDER BY id DESC LIMIT 1
                """, (rs, i) -> rs.getString(1), userId)
                .stream().findFirst().orElse("not_requested");
        return switch (status) {
            case "pending", "processing", "retryable", "failed" -> false;
            default -> true;
        };
    }

    private static String compactError(String error) {
        if (error == null || error.isBlank()) return "remote memory operation failed";
        return error.length() <= 500 ? error : error.substring(0, 500);
    }

    private static List<String> textArray(Array array) throws SQLException {
        if (array == null) return List.of();
        Object raw = array.getArray();
        if (!(raw instanceof Object[] values)) return List.of();
        List<String> result = new ArrayList<>(values.length);
        for (Object value : values) {
            if (value != null && !value.toString().isBlank()) result.add(value.toString());
        }
        return List.copyOf(result);
    }
}
