package com.tutor.chat.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** turn_traces 节点级span记录 (实现设计 6.3)。失败只记日志, 永不影响主链路。 */
@Component
public class TraceRecorder {
    private static final Logger log = LoggerFactory.getLogger(TraceRecorder.class);
    private final JdbcTemplate jdbc;
    private final Executor persistenceExecutor;
    private final Counter dropped;
    private final ObjectMapper mapper = new ObjectMapper();

    public TraceRecorder(JdbcTemplate jdbc, @Qualifier("tracePersistenceExecutor") Executor persistenceExecutor,
                         MeterRegistry registry) {
        this.jdbc = jdbc;
        this.persistenceExecutor = persistenceExecutor;
        this.dropped = Counter.builder("tutor.trace.persistence.dropped").register(registry);
    }

    public void span(String traceId, Long conversationId, String node, long startMs, boolean degraded) {
        span(traceId, conversationId, node, startMs, degraded, null);
    }

    public void span(String traceId, Long conversationId, String node, long startMs,
                     boolean degraded, Map<String, Object> snapshot) {
        try {
            String snapshotJson = snapshot == null ? null : mapper.writeValueAsString(snapshot);
            int durationMs = (int) (System.currentTimeMillis() - startMs);
            persistenceExecutor.execute(() -> persist(traceId, conversationId, node, snapshotJson, durationMs, degraded));
        } catch (RejectedExecutionException e) {
            dropped.increment();
            log.debug("trace persistence queue is full trace={}", traceId);
        } catch (Exception e) {
            log.warn("trace记录失败: {}", e.getMessage());
        }
    }

    private void persist(String traceId, Long conversationId, String node, String snapshotJson,
                         int durationMs, boolean degraded) {
        try {
            jdbc.update("INSERT INTO turn_traces (trace_id, conversation_id, node, snapshot, duration_ms, degraded) VALUES (?,?,?,?::jsonb,?,?)",
                    traceId, conversationId, node, snapshotJson, durationMs, degraded);
        } catch (Exception e) {
            log.warn("trace记录失败: {}", e.getMessage());
        }
    }
}
