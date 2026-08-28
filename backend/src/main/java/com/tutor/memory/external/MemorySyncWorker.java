package com.tutor.memory.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 仅执行已准入且持久化的 outbox 操作；绝不处理原始聊天轮次。 */
@Component
public class MemorySyncWorker {
    private static final Logger log = LoggerFactory.getLogger(MemorySyncWorker.class);

    private final MemorySyncOutbox outbox;
    private final Mem0Client mem0;
    private final Mem0CircuitBreaker breaker;

    public MemorySyncWorker(MemorySyncOutbox outbox, Mem0Client mem0, Mem0CircuitBreaker breaker) {
        this.outbox = outbox;
        this.mem0 = mem0;
        this.breaker = breaker;
    }

    @Scheduled(fixedDelayString = "${memory.sync.poll-ms:1000}")
    public void processOne() {
        if (!mem0.enabled() || !breaker.allowRequest()) return;
        outbox.claimNext().ifPresent(this::process);
    }

    private void process(MemorySyncOutbox.Job job) {
        try {
            if (!"delete_user".equals(job.operation()) && !"upsert_memory".equals(job.operation())
                    && !"delete_memory".equals(job.operation())) {
                throw new IllegalStateException("unsupported memory sync operation");
            }
            boolean current = "upsert_memory".equals(job.operation())
                    ? job.memoryId() != null && outbox.isUpsertAllowed(
                    job.userId(), job.memoryGeneration(), job.memoryId())
                    : outbox.isGenerationCurrent(job.userId(), job.memoryGeneration());
            if (!current) {
                // 后续的授权变更或清除操作已取代本次同步，旧事件不能覆盖新状态。
                complete(job);
                log.info("Mem0 memory sync superseded user={} operation={} job={}",
                        job.userId(), job.operation(), job.id());
                return;
            }
            if ("delete_user".equals(job.operation())) {
                mem0.deleteAllForUser(job.userId());
            } else if ("delete_memory".equals(job.operation())) {
                if (job.remoteMemoryId() != null) {
                    mem0.deleteMemory(job.remoteMemoryId());
                } else if (job.memoryId() == null || !mem0.deleteMemoryForLocalId(job.userId(), job.memoryId())) {
                    // 添加接口是异步的，UUID 可能尚未可见；保留任务让退避/人工重试继续发现。
                    throw new IllegalStateException("remote memory id not found yet");
                }
            } else {
                mem0.addAdmittedMemory(job.userId(), job.memoryId() == null ? 0L : job.memoryId(),
                        job.memoryGeneration(), job.summary(), job.topics(), job.openItems(),
                        "memory-sync-" + job.id());
            }
            breaker.success();
            complete(job);
            log.info("Mem0 memory sync completed user={} operation={} job={}",
                    job.userId(), job.operation(), job.id());
        } catch (RuntimeException error) {
            breaker.failure();
            fail(job, error.getMessage());
            log.warn("Mem0 memory sync failed job={} attempt={}: {}", job.id(), job.attemptCount(), error.getMessage());
        }
    }

    private void complete(MemorySyncOutbox.Job job) {
        if (job.leaseToken() == null) outbox.complete(job.id());
        else outbox.complete(job.id(), job.leaseToken());
    }

    private void fail(MemorySyncOutbox.Job job, String error) {
        if (job.leaseToken() == null) outbox.fail(job.id(), job.attemptCount(), error);
        else outbox.fail(job.id(), job.leaseToken(), job.attemptCount(), error);
    }
}
