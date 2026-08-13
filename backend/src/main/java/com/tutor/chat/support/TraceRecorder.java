package com.tutor.chat.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** turn_traces 节点级span记录 (实现设计 6.3)。失败只记日志, 永不影响主链路。 */
@Component
public class TraceRecorder {
    private static final Logger log = LoggerFactory.getLogger(TraceRecorder.class);
    private final JdbcTemplate jdbc;

    public TraceRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    //TODO这里直接执行sql语句不会很影响性能吗？
    public void span(String traceId, Long conversationId, String node, long startMs, boolean degraded) {
        try {
            jdbc.update("INSERT INTO turn_traces (trace_id, conversation_id, node, duration_ms, degraded) VALUES (?,?,?,?,?)",
                    traceId, conversationId, node, (int) (System.currentTimeMillis() - startMs), degraded);
        } catch (Exception e) {
            log.warn("trace记录失败: {}", e.getMessage());
        }
    }
}
