package com.tutor.platform.llm;

import com.tutor.platform.config.LlmProperties;
import com.tutor.contract.Purpose;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/** Owns non-streaming provider construction, retries, fallback, and usage accounting. */
final class LlmJsonExecutor {
    private static final Logger log = LoggerFactory.getLogger(LlmJsonExecutor.class);

    private final LlmProperties properties;
    private final LlmRequestPolicy requestPolicy;

    LlmJsonExecutor(LlmProperties properties, LlmRequestPolicy requestPolicy) {
        this.properties = properties;
        this.requestPolicy = requestPolicy;
    }

    interface Accounting {
        void settle(long actualTokens);

        void record(String traceId, Purpose purpose, String model,
                    long inputTokens, long outputTokens, long durationMs, String status);

        void calibrate(long estimated, long measured);
    }

    String execute(Purpose purpose, List<ChatMessage> messages, String traceId,
                   Duration timeout, int maxAttempts, long perAttemptEstimate,
                   long reserved, BudgetPressureService budgetPressure, Accounting accounting) {
        RuntimeException last = null;
        long actual = 0;
        long startedAt = System.currentTimeMillis();
        try {
            String model = properties.routing().getOrDefault(
                    purpose.name().toLowerCase(), "deepseek-chat");
            OpenAiChatModel primary = buildModel(properties.deepseek(), model, purpose,
                    timeout, budgetPressure);
            long failedEstimate = 0;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    ChatResponse response = primary.chat(messages);
                    TokenUsage usage = response.tokenUsage();
                    long measured = usageInput(usage) + usageOutput(usage);
                    if (measured == 0) measured = perAttemptEstimate;
                    actual = cappedAdd(failedEstimate, measured, reserved);
                    accounting.calibrate(perAttemptEstimate, measured);
                    accounting.record(traceId, purpose, model,
                            usageInput(usage), usageOutput(usage),
                            System.currentTimeMillis() - startedAt, "ok");
                    return response.aiMessage().text();
                } catch (RuntimeException error) {
                    last = error;
                    failedEstimate = cappedAdd(failedEstimate, perAttemptEstimate, reserved);
                    log.warn("chatJson {} attempt {} failed type={} detail={}", purpose, attempt,
                            error.getClass().getSimpleName(), safeErrorMessage(error));
                }
            }

            LlmProperties.Fallback fallback = properties.fallbackOrDisabled();
            if (fallback.isConfigured()) {
                String fallbackModel = fallback.modelFor(purpose.name().toLowerCase(), model);
                try {
                    ChatResponse response = buildModel(fallback.endpoint(), fallbackModel, purpose,
                            timeout, budgetPressure).chat(messages);
                    TokenUsage usage = response.tokenUsage();
                    long measured = usageInput(usage) + usageOutput(usage);
                    if (measured == 0) measured = perAttemptEstimate;
                    actual = cappedAdd(failedEstimate, measured, reserved);
                    accounting.calibrate(perAttemptEstimate, measured);
                    accounting.record(traceId, purpose, fallbackModel,
                            usageInput(usage), usageOutput(usage),
                            System.currentTimeMillis() - startedAt, "ok_fallback");
                    return response.aiMessage().text();
                } catch (RuntimeException error) {
                    last = error;
                    failedEstimate = cappedAdd(failedEstimate, perAttemptEstimate, reserved);
                    log.warn("chatJson {} fallback failed type={} detail={}", purpose,
                            error.getClass().getSimpleName(), safeErrorMessage(error));
                }
            }
            actual = failedEstimate;
            accounting.record(traceId, purpose, model, failedEstimate, 0,
                    System.currentTimeMillis() - startedAt, "error_estimated");
            throw last;
        } finally {
            accounting.settle(actual);
        }
    }

    private OpenAiChatModel buildModel(LlmProperties.Endpoint endpoint, String model,
                                       Purpose purpose, Duration timeout,
                                       BudgetPressureService budgetPressure) {
        return OpenAiChatModel.builder()
                .apiKey(endpoint.apiKey())
                .baseUrl(endpoint.baseUrl())
                .modelName(model)
                .temperature(0.0)
                .responseFormat("json_object")
                .maxTokens(requestPolicy.outputLimit(purpose, budgetPressure))
                // Gateway explicitly owns retry policy; avoid hidden SDK attempts.
                .maxRetries(0)
                .timeout(timeout)
                .build();
    }

    private static long usageInput(TokenUsage usage) {
        return usage == null || usage.inputTokenCount() == null
                ? 0 : Math.max(0, usage.inputTokenCount());
    }

    private static long usageOutput(TokenUsage usage) {
        return usage == null || usage.outputTokenCount() == null
                ? 0 : Math.max(0, usage.outputTokenCount());
    }

    private static long cappedAdd(long left, long right, long cap) {
        long safeLeft = Math.max(0, left);
        long safeRight = Math.max(0, right);
        if (safeLeft >= cap || safeRight > cap - safeLeft) return cap;
        return safeLeft + safeRight;
    }

    private static String safeErrorMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "";
        String oneLine = message.replaceAll("[\\r\\n\\t]", " ").trim();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "…";
    }
}
