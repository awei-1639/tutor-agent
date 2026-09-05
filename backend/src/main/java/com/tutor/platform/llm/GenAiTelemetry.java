package com.tutor.platform.llm;

import com.tutor.contract.Purpose;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 按 OpenTelemetry gen_ai.* 语义约定发出 LLM 调用 span。
 * tracer 为 no-op 时零开销。span 在调用完成后补记 (已知耗时)，因此不改变主链路时序。
 */
final class GenAiTelemetry {
    private static final AttributeKey<String> SYSTEM = AttributeKey.stringKey("gen_ai.system");
    private static final AttributeKey<String> OPERATION = AttributeKey.stringKey("gen_ai.operation.name");
    private static final AttributeKey<String> MODEL = AttributeKey.stringKey("gen_ai.request.model");
    private static final AttributeKey<Long> INPUT_TOKENS = AttributeKey.longKey("gen_ai.usage.input_tokens");
    private static final AttributeKey<Long> OUTPUT_TOKENS = AttributeKey.longKey("gen_ai.usage.output_tokens");
    private static final AttributeKey<String> TRACE_ID = AttributeKey.stringKey("tutor.trace_id");
    private static final AttributeKey<String> STATUS = AttributeKey.stringKey("tutor.status");

    private final Tracer tracer;

    GenAiTelemetry(Tracer tracer) {
        this.tracer = tracer;
    }

    void recordCall(String traceId, Purpose purpose, String model,
                    long inputTokens, long outputTokens, long durationMs, String status) {
        if (tracer == null) return;
        Instant end = Instant.now();
        Instant start = end.minus(Math.max(0, durationMs), ChronoUnit.MILLIS);
        Span span = tracer.spanBuilder("gen_ai." + operationName(purpose))
                .setSpanKind(SpanKind.CLIENT)
                .setStartTimestamp(start)
                .startSpan();
        try {
            span.setAttribute(SYSTEM, "deepseek");
            span.setAttribute(OPERATION, operationName(purpose));
            span.setAttribute(MODEL, model == null ? "" : model);
            span.setAttribute(INPUT_TOKENS, Math.max(0, inputTokens));
            span.setAttribute(OUTPUT_TOKENS, Math.max(0, outputTokens));
            if (traceId != null) span.setAttribute(TRACE_ID, traceId);
            if (status != null) span.setAttribute(STATUS, status);
            if (status != null && status.startsWith("error")) {
                span.setStatus(StatusCode.ERROR);
            }
        } finally {
            span.end(end);
        }
    }

    private static String operationName(Purpose purpose) {
        return switch (purpose) {
            case EMBED -> "embeddings";
            case RERANK -> "rerank";
            default -> "chat";
        };
    }
}
