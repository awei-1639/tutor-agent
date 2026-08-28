package com.tutor.knowledge;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/** 积压、失败和租约围栏的低基数入库指标。 */
@Component
public class KnowledgeIngestionMetrics {
    private final JdbcTemplate jdbc;
    private final AtomicInteger backlog = new AtomicInteger();
    private final Counter failures;
    private final Counter leaseExpired;

    public KnowledgeIngestionMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.failures = registry.counter("knowledge.ingestion.failures");
        this.leaseExpired = registry.counter("knowledge.ingestion.lease_expired");
        Gauge.builder("knowledge.ingestion.backlog", backlog, AtomicInteger::get).register(registry);
    }

    public void failure() { failures.increment(); }
    public void leaseExpired() { leaseExpired.increment(); }
    public void refreshBacklog() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM knowledge_ingestion_jobs WHERE status IN ('pending','retryable_failed','processing')", Integer.class);
        backlog.set(count == null ? 0 : count);
    }
}
