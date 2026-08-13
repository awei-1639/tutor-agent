package com.tutor.memory.external;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/** Transactional intent log for external-memory deletion. */
@Component
public class MemorySyncOutbox {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public MemorySyncOutbox(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public void enqueueDeleteUser(long userId) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO memory_sync_outbox (user_id, memory_generation, operation)
                SELECT id, memory_generation, 'delete_user' FROM users WHERE id=?
                """, userId));
    }

    public record Job(long id, long userId, long memoryGeneration, String operation, int attemptCount) {}
    public record DeletionStatus(String status, int attemptCount, String lastError) {}

    /** Claims at most one due job; SKIP LOCKED is safe across application instances. */
    public Optional<Job> claimNext() {
        return Optional.ofNullable(transactions.execute(status -> {
            var jobs = jdbc.query("""
                    SELECT id, user_id, memory_generation, operation, attempt_count
                    FROM memory_sync_outbox
                    WHERE status IN ('pending', 'retryable') AND next_attempt_at <= now()
                    ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1
                    """, (rs, i) -> new Job(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                    rs.getString(4), rs.getInt(5)));
            if (jobs.isEmpty()) return null;
            Job job = jobs.getFirst();
            jdbc.update("UPDATE memory_sync_outbox SET status='processing', attempt_count=attempt_count+1 WHERE id=?", job.id());
            return new Job(job.id(), job.userId(), job.memoryGeneration(), job.operation(), job.attemptCount() + 1);
        }));
    }

    public void complete(long jobId) {
        jdbc.update("UPDATE memory_sync_outbox SET status='completed', completed_at=now(), last_error=NULL WHERE id=?", jobId);
    }

    public void fail(long jobId, int attemptCount, String error) {
        boolean retryable = attemptCount < 5;
        jdbc.update("""
                UPDATE memory_sync_outbox
                SET status=?, last_error=?, next_attempt_at=CASE WHEN ? THEN now() + (? * interval '10 seconds') ELSE next_attempt_at END
                WHERE id=?
                """, retryable ? "retryable" : "failed", compactError(error), retryable,
                Math.min(attemptCount, 12), jobId);
    }

    public DeletionStatus latestDeletionStatus(long userId) {
        return jdbc.query("""
                SELECT status, attempt_count, last_error FROM memory_sync_outbox
                WHERE user_id=? AND operation='delete_user'
                ORDER BY id DESC LIMIT 1
                """, (rs, i) -> new DeletionStatus(rs.getString(1), rs.getInt(2), rs.getString(3)), userId)
                .stream().findFirst().orElse(new DeletionStatus("not_requested", 0, null));
    }

    private static String compactError(String error) {
        if (error == null || error.isBlank()) return "remote memory operation failed";
        return error.length() <= 500 ? error : error.substring(0, 500);
    }
}
