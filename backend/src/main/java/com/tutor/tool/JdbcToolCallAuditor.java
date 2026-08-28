package com.tutor.tool;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcToolCallAuditor implements ToolCallAuditor {
    private final JdbcTemplate jdbc;

    public JdbcToolCallAuditor(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void record(ToolCallRecord call) {
        jdbc.update("""
                INSERT INTO tool_calls(trace_id, agent, tool, args_digest, status, side_effect, duration_ms, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, call.traceId(), call.agent(), call.tool(), call.argsDigest(), call.status(), call.sideEffect(),
                Math.toIntExact(Math.min(Integer.MAX_VALUE, Math.max(0, call.durationMs()))), call.idempotencyKey());
    }
}
