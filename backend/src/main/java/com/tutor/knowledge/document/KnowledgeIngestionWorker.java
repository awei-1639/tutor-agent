package com.tutor.knowledge.document;

import com.tutor.config.KnowledgeIngestionProperties;
import com.tutor.config.ExecutorLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 调度器仅认领持久化任务；所有发布检查均由服务层执行。 */
@Component
public class KnowledgeIngestionWorker {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionWorker.class);
    private final KnowledgeIngestionJobStore jobs;
    private final KnowledgeDocumentService documents;
    private final KnowledgeIngestionMetrics metrics;
    private final java.util.concurrent.Semaphore globalSlot;
    private final ExecutorService executor;

    @Autowired
    public KnowledgeIngestionWorker(KnowledgeIngestionJobStore jobs, KnowledgeDocumentService documents, KnowledgeIngestionMetrics metrics,
                                    KnowledgeIngestionProperties properties) {
        this.jobs = jobs;
        this.documents = documents;
        this.metrics = metrics;
        this.globalSlot = new java.util.concurrent.Semaphore(properties.maxInFlight());
        this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
    }

    public KnowledgeIngestionWorker(KnowledgeIngestionJobStore jobs, KnowledgeDocumentService documents, KnowledgeIngestionMetrics metrics) {
        this.jobs = jobs;
        this.documents = documents;
        this.metrics = metrics;
        this.globalSlot = new java.util.concurrent.Semaphore(1);
        this.executor = null;
    }

    public KnowledgeIngestionWorker(KnowledgeIngestionJobStore jobs, KnowledgeDocumentService documents) {
        this(jobs, documents, null);
    }

    @Scheduled(fixedDelayString = "${knowledge.ingestion.poll-ms:1000}")
    public void processOne() {
        if (metrics != null) metrics.refreshBacklog();
        if (!globalSlot.tryAcquire()) return;
        Optional<KnowledgeIngestionJobStore.Job> claimed;
        try {
            claimed = jobs.claimNext();
        } catch (RuntimeException error) {
            globalSlot.release();
            throw error;
        }
        if (claimed.isEmpty()) {
            globalSlot.release();
            return;
        }
        if (executor == null) {
            processClaimed(claimed.get());
            globalSlot.release();
        } else {
            executor.submit(() -> {
                try { processClaimed(claimed.get()); }
                finally { globalSlot.release(); }
            });
        }
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) ExecutorLifecycle.shutdown(executor, "knowledge-ingestion", log);
    }

    private void processClaimed(KnowledgeIngestionJobStore.Job job) {
        try {
            documents.ingest(job);
            requireLease(jobs.complete(job));
        } catch (Exception error) {
            boolean terminal = jobs.failFenced(job, error.getMessage());
            if (terminal) jobs.markDocumentFailed(job.documentId(), job.documentGeneration(), error.getMessage());
            if (metrics != null) metrics.failure();
            log.warn("knowledge ingestion failed job={} attempt={}: {}", job.id(), job.attempts(), error.getMessage());
        }
    }

    private static void requireLease(boolean acquired) {
        if (!acquired) throw new IllegalStateException("知识摄取任务租约已失效");
    }
}
