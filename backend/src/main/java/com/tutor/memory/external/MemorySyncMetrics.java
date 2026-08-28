package com.tutor.memory.external;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/** 外部记忆同步的低基数运行指标；指标查询失败不能影响主业务或 worker。 */
@Component
public class MemorySyncMetrics {
    private static final Logger log = LoggerFactory.getLogger(MemorySyncMetrics.class);

    private final JdbcTemplate jdbc;
    private final AtomicInteger backlog = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();

    public MemorySyncMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("tutor.memory.sync.backlog", backlog, AtomicInteger::get).register(registry);
        Gauge.builder("tutor.memory.sync.failed", failed, AtomicInteger::get).register(registry);
    }

    @Scheduled(fixedDelayString = "${memory.sync.metrics-refresh-ms:10000}")
    public void refresh() {
        try {
            Integer pending = jdbc.queryForObject("""
                    SELECT count(*) FROM memory_sync_outbox
                    WHERE status IN ('pending', 'retryable', 'processing')
                    """, Integer.class);
            Integer terminalFailed = jdbc.queryForObject(
                    "SELECT count(*) FROM memory_sync_outbox WHERE status='failed'", Integer.class);
            backlog.set(pending == null ? 0 : pending);
            failed.set(terminalFailed == null ? 0 : terminalFailed);
        } catch (RuntimeException e) {
            log.debug("刷新外部记忆同步指标失败: {}", e.getMessage());
        }
    }

    int backlog() {
        return backlog.get();
    }

    int failed() {
        return failed.get();
    }
}
