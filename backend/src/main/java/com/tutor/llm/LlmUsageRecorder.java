package com.tutor.llm;

import com.tutor.contract.Purpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/** 以尽力而为方式记录 LLM 用量，记账失败不得影响主请求。 */
final class LlmUsageRecorder {
    private static final Logger log = LoggerFactory.getLogger(LlmUsageRecorder.class);
    private final JdbcTemplate jdbc;

    LlmUsageRecorder(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void record(String traceId, Purpose purpose, String model, long input, long output, long durationMs, String status) {
        try {
            jdbc.update("INSERT INTO llm_usage (trace_id, purpose, model, tokens_in, tokens_out, duration_ms, status) VALUES (?,?,?,?,?,?,?)",
                    traceId, purpose.name().toLowerCase(), model, input, output, (int) durationMs, status);
        } catch (Exception e) {
            log.error("llm_usage 记账失败(不阻塞主链路): {}", e.getMessage());
        }
    }
}
