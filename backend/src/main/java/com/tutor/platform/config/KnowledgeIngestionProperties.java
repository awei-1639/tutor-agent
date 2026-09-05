package com.tutor.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 持久化入库吞吐量和队列准入的运行时配置项。 */
@ConfigurationProperties(prefix = "knowledge.ingestion")
public record KnowledgeIngestionProperties(int pollMs, int leaseSeconds, int maxInFlight,
                                           int embeddingConcurrency, int maxPendingJobs) {
    public KnowledgeIngestionProperties {
        pollMs = pollMs <= 0 ? 1000 : pollMs;
        leaseSeconds = leaseSeconds <= 0 ? 1800 : Math.max(60, leaseSeconds);
        maxInFlight = maxInFlight <= 0 ? 4 : maxInFlight;
        embeddingConcurrency = embeddingConcurrency <= 0 ? 8 : embeddingConcurrency;
        maxPendingJobs = maxPendingJobs <= 0 ? 1000 : maxPendingJobs;
    }

    public static KnowledgeIngestionProperties defaults() {
        return new KnowledgeIngestionProperties(1000, 1800, 4, 8, 1000);
    }
}
