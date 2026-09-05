package com.tutor.conversation.memory.external;

import com.tutor.conversation.memory.policy.MemoryAdmissionPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 外部记忆同步操作的事务性意图日志。 */
@Component
public class MemorySyncOutbox {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final MemoryAdmissionPolicy admission;
    private final MemorySyncJobStore jobStore;
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
        this.jobStore = new MemorySyncJobStore(jdbc, transactions, this.leaseSeconds);
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
        if (remoteMemoryId != null && !com.tutor.conversation.memory.RemoteMemoryId.isValid(remoteMemoryId)) return;
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
        return jobStore.claimNext();
    }

    public void complete(long jobId) {
        jobStore.complete(jobId);
    }

    /** 只有当前租约持有者才能完成任务，旧 worker 的迟到回调会被忽略。 */
    public void complete(long jobId, UUID leaseToken) {
        jobStore.complete(jobId, leaseToken);
    }

    public void fail(long jobId, int attemptCount, String error) {
        jobStore.fail(jobId, attemptCount, error);
    }

    /** 只有当前租约持有者才能推进失败状态。 */
    public void fail(long jobId, UUID leaseToken, int attemptCount, String error) {
        jobStore.fail(jobId, leaseToken, attemptCount, error);
    }

    /**
     * 将当前用户、当前记忆代次中已经耗尽自动重试次数的任务重新排队。
     *
     * 记忆代次由数据库读取并参与条件判断，避免清除记忆后把旧任务重新唤醒。
     * 该操作只重置失败任务；仍在自动退避中的任务不会被重复提交。
     */
    public int requeueFailedForUser(long userId) {
        return jobStore.requeueFailedForUser(userId);
    }

    public boolean isGenerationCurrent(long userId, long generation) {
        return jobStore.isGenerationCurrent(userId, generation);
    }

    public boolean isUpsertAllowed(long userId, long generation, long memoryId) {
        return jobStore.isUpsertAllowed(userId, generation, memoryId);
    }

    public DeletionStatus latestDeletionStatus(long userId) {
        return jobStore.latestDeletionStatus(userId);
    }

    /** 查询当前用户某条本地记忆对应的最近远程删除任务。 */
    public Optional<DeletionStatus> latestDeletionStatus(long userId, long memoryId) {
        return jobStore.latestDeletionStatus(userId, memoryId);
    }

    public boolean remoteReadAllowed(long userId) {
        return jobStore.remoteReadAllowed(userId);
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

}
