package com.tutor.memory.external;

import com.tutor.memory.policy.MemoryAdmissionPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 外部记忆同步操作的事务性意图日志。 */
@Component
public class MemorySyncOutbox {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final MemoryAdmissionPolicy admission;
    private final int leaseSeconds;

    public MemorySyncOutbox(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this(jdbc, transactions, new MemoryAdmissionPolicy(), 300);
    }

    public MemorySyncOutbox(JdbcTemplate jdbc, TransactionTemplate transactions,
                            MemoryAdmissionPolicy admission) {
        this(jdbc, transactions, admission, 300);
    }

    @Autowired
    public MemorySyncOutbox(JdbcTemplate jdbc, TransactionTemplate transactions,
                            MemoryAdmissionPolicy admission,
                            @Value("${memory.sync.lease-seconds:300}") int leaseSeconds) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.admission = admission;
        this.leaseSeconds = Math.clamp(leaseSeconds, 30, 3600);
    }

    public void enqueueDeleteUser(long userId) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO memory_sync_outbox (user_id, memory_generation, operation)
                SELECT id, memory_generation, 'delete_user' FROM users WHERE id=?
                """, userId));
    }

    /** 在 Episode 本地事务中登记一条已准入的远程副本更新。 */
    public void enqueueUpsertEpisode(long userId, long memoryGeneration, long memoryId,
                                     String summary, List<String> topics, List<String> openItems) {
        if (!admission.acceptsEpisode(summary, topics, openItems)) return;
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO memory_sync_outbox
                    (user_id, memory_generation, operation, memory_id, summary, topics, open_items)
                SELECT id, memory_generation, 'upsert_memory', ?, ?, ?::text[], ?::text[]
                FROM users
                WHERE id=? AND external_memory_enabled=true AND memory_generation=?
                ON CONFLICT (operation, memory_id, memory_generation)
                    WHERE operation='upsert_memory' AND memory_id IS NOT NULL DO NOTHING
                """, memoryId, summary, toPgTextArrayLiteral(topics), toPgTextArrayLiteral(openItems),
                userId, memoryGeneration));
    }

    /** 在本地墓碑事务中登记一条 Mem0 单项删除事件。 */
    public void enqueueDeleteMemory(long userId, long memoryId, String remoteMemoryId) {
        if (memoryId <= 0) return;
        if (remoteMemoryId != null && !com.tutor.memory.RemoteMemoryId.isValid(remoteMemoryId)) return;
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO memory_sync_outbox
                    (user_id, memory_generation, operation, memory_id, remote_memory_id)
                SELECT id, memory_generation, 'delete_memory', ?, ?
                FROM users WHERE id=?
                ON CONFLICT (user_id, operation, memory_id, memory_generation)
                    WHERE operation='delete_memory' AND memory_id IS NOT NULL DO NOTHING
                """, memoryId, remoteMemoryId, userId));
    }

    public record Job(long id, long userId, long memoryGeneration, String operation,
                      Long memoryId, String remoteMemoryId, String summary,
                      List<String> topics, List<String> openItems,
                      int attemptCount, UUID leaseToken) {
        public Job(long id, long userId, long memoryGeneration, String operation,
                   Long memoryId, String summary, List<String> topics, List<String> openItems,
                   int attemptCount) {
            this(id, userId, memoryGeneration, operation, memoryId, null, summary, topics, openItems,
                    attemptCount, null);
        }

        public Job(long id, long userId, long memoryGeneration, String operation,
                   Long memoryId, String summary, List<String> topics, List<String> openItems,
                   int attemptCount, UUID leaseToken) {
            this(id, userId, memoryGeneration, operation, memoryId, null, summary, topics, openItems,
                    attemptCount, leaseToken);
        }

        public Job(long id, long userId, long memoryGeneration, String operation,
                   Long memoryId, String remoteMemoryId, String summary,
                   List<String> topics, List<String> openItems, int attemptCount) {
            this(id, userId, memoryGeneration, operation, memoryId, remoteMemoryId, summary,
                    topics, openItems, attemptCount, null);
        }

        public Job(long id, long userId, long memoryGeneration, String operation, int attemptCount) {
            this(id, userId, memoryGeneration, operation, null, null, null, List.of(), List.of(), attemptCount, null);
        }
    }
    public record DeletionStatus(String status, int attemptCount, String lastError) {}

    /** 至多认领一个到期任务；SKIP LOCKED 可安全用于多应用实例。 */
    public Optional<Job> claimNext() {
        return Optional.ofNullable(transactions.execute(status -> {
            var jobs = jdbc.query("""
                    SELECT id, user_id, memory_generation, operation, memory_id, remote_memory_id,
                           summary, topics, open_items,
                           attempt_count
                    FROM memory_sync_outbox
                    WHERE (status IN ('pending', 'retryable') AND next_attempt_at <= now())
                       OR (status='processing' AND lease_until <= now())
                    ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1
                    """, (rs, i) -> new Job(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                    rs.getString(4), rs.getObject(5, Long.class), rs.getString(6), rs.getString(7),
                    textArray(rs.getArray(8)), textArray(rs.getArray(9)), rs.getInt(10)));
            if (jobs.isEmpty()) return null;
            Job job = jobs.getFirst();
            UUID leaseToken = UUID.randomUUID();
            jdbc.update("""
                    UPDATE memory_sync_outbox
                    SET status='processing', attempt_count=attempt_count+1,
                        lease_token=?, lease_until=now() + (? * interval '1 second')
                    WHERE id=?
                    """, leaseToken, leaseSeconds, job.id());
            return new Job(job.id(), job.userId(), job.memoryGeneration(), job.operation(), job.memoryId(),
                    job.remoteMemoryId(), job.summary(), job.topics(), job.openItems(),
                    job.attemptCount() + 1, leaseToken);
        }));
    }

    public void complete(long jobId) {
        jdbc.update("""
                UPDATE memory_sync_outbox
                SET status='completed', completed_at=now(), last_error=NULL,
                    lease_token=NULL, lease_until=NULL
                WHERE id=?
                """, jobId);
    }

    /** 只有当前租约持有者才能完成任务，旧 worker 的迟到回调会被忽略。 */
    public void complete(long jobId, UUID leaseToken) {
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

    public void fail(long jobId, int attemptCount, String error) {
        fail(jobId, null, attemptCount, error);
    }

    /** 只有当前租约持有者才能推进失败状态。 */
    public void fail(long jobId, UUID leaseToken, int attemptCount, String error) {
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

    /**
     * 将当前用户、当前记忆代次中已经耗尽自动重试次数的任务重新排队。
     *
     * 记忆代次由数据库读取并参与条件判断，避免清除记忆后把旧任务重新唤醒。
     * 该操作只重置失败任务；仍在自动退避中的任务不会被重复提交。
     */
    public int requeueFailedForUser(long userId) {
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

    public boolean isGenerationCurrent(long userId, long generation) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE id=? AND memory_generation=?",
                Integer.class, userId, generation);
        return count != null && count > 0;
    }

    public boolean isUpsertAllowed(long userId, long generation, long memoryId) {
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

    public DeletionStatus latestDeletionStatus(long userId) {
        return jdbc.query("""
                SELECT status, attempt_count, last_error FROM memory_sync_outbox
                WHERE user_id=? AND operation IN ('delete_user', 'delete_memory')
                ORDER BY id DESC LIMIT 1
                """, (rs, i) -> new DeletionStatus(rs.getString(1), rs.getInt(2), rs.getString(3)), userId)
                .stream().findFirst().orElse(new DeletionStatus("not_requested", 0, null));
    }

    /** 查询当前用户某条本地记忆对应的最近远程删除任务。 */
    public Optional<DeletionStatus> latestDeletionStatus(long userId, long memoryId) {
        return jdbc.query("""
                SELECT status, attempt_count, last_error FROM memory_sync_outbox
                WHERE user_id=? AND memory_id=? AND operation='delete_memory'
                ORDER BY id DESC LIMIT 1
                """, (rs, i) -> new DeletionStatus(rs.getString(1), rs.getInt(2), rs.getString(3)),
                userId, memoryId).stream().findFirst();
    }

    public boolean remoteReadAllowed(long userId) {
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

    private static String toPgTextArrayLiteral(List<String> items) {
        if (items == null || items.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            String value = items.get(i) == null ? "" : items.get(i);
            sb.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return sb.append('}').toString();
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
