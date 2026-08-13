package com.tutor.memory.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Performs only durable, admitted outbox operations; it never handles raw chat turns. */
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
            if (!"delete_user".equals(job.operation())) {
                throw new IllegalStateException("unsupported memory sync operation");
            }
            mem0.deleteAllForUser(job.userId());
            breaker.success();
            outbox.complete(job.id());
            log.info("Mem0 memory deletion completed user={} job={}", job.userId(), job.id());
        } catch (RuntimeException error) {
            breaker.failure();
            outbox.fail(job.id(), job.attemptCount(), error.getMessage());
            log.warn("Mem0 memory sync failed job={} attempt={}: {}", job.id(), job.attemptCount(), error.getMessage());
        }
    }
}
