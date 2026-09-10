package com.tutor.platform.llm;

import com.tutor.contract.Purpose;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/** 以尽力而为方式记录 LLM 用量并上报 Micrometer 计数, 记账失败不得影响主请求。 */
final class LlmUsageRecorder {
    private static final Logger log = LoggerFactory.getLogger(LlmUsageRecorder.class);
    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;

    LlmUsageRecorder(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    void record(String traceId, Purpose purpose, String model, long input, long output, long durationMs, String status) {
        try {
            jdbc.update("INSERT INTO llm_usage (trace_id, purpose, model, tokens_in, tokens_out, duration_ms, status) VALUES (?,?,?,?,?,?,?)",
                    traceId, purpose.name().toLowerCase(), model, input, output, (int) durationMs, status);
        } catch (Exception e) {
            log.error("llm_usage 记账失败(不阻塞主链路): {}", e.getMessage());
        }
        // 计数与数据库解耦: DB 抖动时 Prometheus 面板仍反映真实调用量
        try {
            String p = purpose.name().toLowerCase();
            registry.counter("tutor.llm.calls", "purpose", p, "model", model, "status", status).increment();
            if (input > 0) {
                registry.counter("tutor.llm.tokens", "purpose", p, "model", model, "direction", "input").increment(input);
            }
            if (output > 0) {
                registry.counter("tutor.llm.tokens", "purpose", p, "model", model, "direction", "output").increment(output);
            }
        } catch (Exception e) {
            log.debug("LLM 指标上报失败: {}", e.getMessage());
        }
    }
}
