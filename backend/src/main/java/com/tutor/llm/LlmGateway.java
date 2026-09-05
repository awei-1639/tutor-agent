package com.tutor.llm;

import com.tutor.config.LlmProperties;
import com.tutor.context.TokenBudget;
import com.tutor.contract.CancellationToken;
import com.tutor.contract.Evidence;
import com.tutor.contract.Purpose;
import com.tutor.llm.structured.RetrievalJudgeOutput;
import com.tutor.llm.structured.StructuredOutputRecorder;
import com.tutor.llm.structured.StructuredOutputResult;
import com.tutor.llm.structured.StructuredOutputService;
import com.tutor.llm.structured.StructuredTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 全项目唯一的 LLM/Embedding 出口 (实现设计 6.1)。
 * 职责: purpose→model 路由、分级超时、轻量重试(Spike1结论)、日限额、llm_usage 记账。
 */
@Component
public class LlmGateway implements EmbeddingGateway, JsonGenerationGateway, StreamingGenerationGateway,
        RerankGateway, RetrievalJudge {
    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);
    private static final int EMBED_MAX_ATTEMPTS = 2;
    private final LlmProperties props;
    private final LlmUsageRecorder usageRecorder;
    private final LlmBudgetGuard budgetGuard;
    private final LlmConcurrencyGate concurrency;
    private final EmbeddingProviderClient embeddingClient;
    private final RerankProviderClient rerankClient;
    private final ChatStreamProviderClient chatStreamClient;
    private final LlmJsonExecutor jsonExecutor;
    private final RetrievalJudgePromptFactory judgePromptFactory = new RetrievalJudgePromptFactory();
    private final TokenBudget tokenBudget = new TokenBudget();
    private final LlmRequestPolicy requestPolicy;
    private final ObjectMapper mapper = new ObjectMapper();
    private final StructuredOutputService structuredOutputService;
    private volatile GenAiTelemetry telemetry = new GenAiTelemetry(null);
    private volatile BudgetPressureService budgetPressure;
    private final LlmStreamingExecutor streamingExecutor;

    public LlmGateway(LlmProperties props, JdbcTemplate jdbc, LlmBudgetGuard budgetGuard,
                      LlmConcurrencyGate concurrency) {
        this(props, jdbc, budgetGuard, concurrency, null);
    }

    @Autowired
    public LlmGateway(LlmProperties props, JdbcTemplate jdbc, LlmBudgetGuard budgetGuard,
                      LlmConcurrencyGate concurrency, StructuredOutputRecorder structuredOutputRecorder) {
        this.props = props;
        this.usageRecorder = new LlmUsageRecorder(jdbc);
        this.budgetGuard = budgetGuard;
        this.concurrency = concurrency;
        this.requestPolicy = new LlmRequestPolicy(props, tokenBudget);
        this.embeddingClient = new EmbeddingProviderClient(props);
        this.rerankClient = new RerankProviderClient(props);
        this.chatStreamClient = new ChatStreamProviderClient(props);
        this.streamingExecutor = new LlmStreamingExecutor(chatStreamClient, tokenBudget, requestPolicy);
        this.jsonExecutor = new LlmJsonExecutor(props, requestPolicy);
        this.structuredOutputService = new StructuredOutputService(this, structuredOutputRecorder);
    }

    @PreDestroy
    void shutdownStreamingExecutor() {
        streamingExecutor.shutdown();
    }

    /** 可选注入 OpenTelemetry tracer；未配置时保持 no-op，不影响测试构造器。 */
    @Autowired(required = false)
    void setTracer(io.opentelemetry.api.trace.Tracer tracer) {
        if (tracer != null) this.telemetry = new GenAiTelemetry(tracer);
    }

    /** 可选注入预算压力感知；注入后 CHAT 输出上限在 ELEVATED 及以上收紧。 */
    @Autowired(required = false)
    void setBudgetPressure(BudgetPressureService budgetPressure) {
        this.budgetPressure = budgetPressure;
    }

    /** 查询/文档向量化。失败重试1次 (429/5xx/超时同一策略, Spike1结论: 轻量即可)。 */
    public float[] embed(String text, String traceId) {
        return embedInternal(text, traceId, false);
    }

    /** 查询 embedding 在输入超长时保留查询上下文及其最终约束。 */
    public float[] embedQuery(String text, String traceId) {
        return embedInternal(text, traceId, true);
    }

    private float[] embedInternal(String text, String traceId, boolean preserveHeadTail) {
        String safeText = preserveHeadTail
                ? tokenBudget.headTail(text, requestPolicy.tokenLimits().embedInputTokens(), 0.6D)
                : requestPolicy.boundedText(text, requestPolicy.tokenLimits().embedInputTokens());
        long perAttemptEstimate = requestPolicy.estimateText(safeText);
        long reserved = scaleEstimate(perAttemptEstimate, EMBED_MAX_ATTEMPTS);
        LlmBudgetGuard.Reservation reservation = budgetGuard.reserve(traceId, reserved, false);
        boolean acquired = false;
        long t0 = System.currentTimeMillis();
        RuntimeException last = null;
        long actual = 0;
        long failedEstimate = 0;
        try {
            concurrency.acquire();
            acquired = true;
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    float[] vector = embeddingClient.embed(safeText);
                    actual = cappedAdd(failedEstimate, tokenBudget.count(safeText), reserved);
                    recordUsage(traceId, Purpose.EMBED, props.routing().get("embed"),
                            actual, 0, System.currentTimeMillis() - t0, "ok");
                    return vector;
                } catch (RuntimeException ex) {
                    last = ex;
                    failedEstimate = cappedAdd(failedEstimate, perAttemptEstimate, reserved);
                    log.warn("embed attempt {} failed type={} detail={}", attempt,
                            ex.getClass().getSimpleName(), safeErrorMessage(ex));
                }
            }
            recordUsage(traceId, Purpose.EMBED, props.routing().get("embed"),
                    failedEstimate, 0, System.currentTimeMillis() - t0, "error_estimated");
            actual = failedEstimate;
            throw last;
        } finally {
            settleAndRelease(reservation, actual, acquired);
        }
    }

    /**
     * 批量生成文档 embedding。批次大小被刻意限制，避免单次供应商请求造成
     * 无界内存占用或限流突发。
     */
    public List<float[]> embedBatch(List<String> texts, String traceId) {
        if (texts == null || texts.isEmpty()) return List.of();
        if (texts.size() > 32) throw new IllegalArgumentException("embedding batch too large");
        List<String> safeTexts = texts.stream()
                .map(text -> requestPolicy.boundedText(text, requestPolicy.tokenLimits().embedInputTokens()))
                .toList();
        long perAttemptEstimate = safeTexts.stream().mapToLong(requestPolicy::estimateText).sum();
        long reserved = scaleEstimate(perAttemptEstimate, EMBED_MAX_ATTEMPTS);
        // 批量嵌入属于知识入库等后台工作，占用后台子预算，不挤占前台可用额度。
        LlmBudgetGuard.Reservation reservation = budgetGuard.reserve(traceId, reserved, true);
        boolean acquired = false;
        long startedAt = System.currentTimeMillis();
        long actual = 0;
        RuntimeException last = null;
        try {
            concurrency.acquire();
            acquired = true;
            for (int attempt = 1; attempt <= EMBED_MAX_ATTEMPTS; attempt++) {
                try {
                    List<float[]> embeddings = embeddingClient.embedBatch(safeTexts);
                    actual = Math.min(reserved, perAttemptEstimate);
                    recordUsage(traceId, Purpose.EMBED, props.routing().get("embed"),
                            actual, 0, System.currentTimeMillis() - startedAt, "ok_batch");
                    return embeddings;
                } catch (RuntimeException ex) {
                    last = ex;
                    actual = Math.min(reserved, actual + perAttemptEstimate);
                    log.warn("embed batch attempt {} failed size={} type={} detail={}", attempt,
                            safeTexts.size(), ex.getClass().getSimpleName(), safeErrorMessage(ex));
                }
            }
            recordUsage(traceId, Purpose.EMBED, props.routing().get("embed"),
                    actual, 0, System.currentTimeMillis() - startedAt, "error_estimated_batch");
            throw last == null ? new IllegalStateException("embedding batch failed") : last;
        } finally {
            settleAndRelease(reservation, actual, acquired);
        }
    }

    /** 流式对话。首 token 前失败可由调用方决定是否重建; 网关负责限额检查与记账。 */
    public void chatStream(Purpose purpose, List<LlmMessage> messages, String traceId,
                           LlmStreamHandler handler) {
        chatStream(purpose, messages, traceId, handler, new CancellationToken());
    }

    /** Synchronous to callers: returns only after the provider stream has settled. */
    public void chatStream(Purpose purpose, List<LlmMessage> messages, String traceId,
                           LlmStreamHandler handler, CancellationToken cancellation) {
        if (cancellation == null) throw new IllegalArgumentException("cancellation must not be null");
        if (cancellation.isCancelled()) return;
        List<ChatMessage> safeMessages = boundedMessages(purpose, LlmMessageMapper.toLangChain(messages));
        long reserved = requestPolicy.estimate(purpose, safeMessages, budgetPressure);
        LlmBudgetGuard.Reservation reservation = budgetGuard.reserve(traceId, reserved, false);
        boolean acquired = false;
        java.util.concurrent.atomic.AtomicBoolean settled = new java.util.concurrent.atomic.AtomicBoolean();
        LlmStreamingExecutor.Accounting accounting = new LlmStreamingExecutor.Accounting() {
            @Override
            public void settle(long actualTokens) {
                if (settled.compareAndSet(false, true)) {
                    settleAndRelease(reservation, actualTokens, true);
                }
            }

            @Override
            public void record(String traceId, Purpose purpose, String model,
                               long inputTokens, long outputTokens, long durationMs, String status) {
                recordUsage(traceId, purpose, model, inputTokens, outputTokens, durationMs, status);
            }

            @Override
            public void calibrate(long estimated, long measured) {
                requestPolicy.calibrate(estimated, measured);
            }
        };
        try {
            concurrency.acquire();
            acquired = true;
            if (cancellation.isCancelled()) {
                accounting.settle(0);
                return;
            }
            String model = props.routing().getOrDefault(purpose.name().toLowerCase(), "deepseek-chat");
            int maxOutputTokens = requestPolicy.outputLimit(purpose, budgetPressure);
            streamingExecutor.stream(purpose, safeMessages, model, traceId, handler,
                    cancellation, maxOutputTokens, reserved, accounting);
        } catch (RuntimeException error) {
            if (acquired) {
                accounting.settle(0);
            } else {
                settleAndRelease(reservation, 0, false);
            }
            throw error;
        }
    }

    /**
     * 非流式 JSON 调用 (画像抽取/router 等结构化用途)。
     * response_format=json_object + 按用途控制重试次数 (背景/判定节点默认单次，
     * 用户可见的抽取/计划保留一次轻量重试)。
     */
    public String chatJson(Purpose purpose, List<LlmMessage> messages, String traceId) {
        return chatJson(purpose, messages, traceId, null, requestPolicy.defaultMaxAttempts(purpose));
    }

    /** Provider retries and fallback are isolated in LlmJsonExecutor. */
    public String chatJson(Purpose purpose, List<LlmMessage> messages, String traceId,
                           Duration requestTimeout, int maxAttempts) {
        if (maxAttempts < 1 || maxAttempts > 2) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 2");
        }
        List<ChatMessage> safeMessages = boundedMessages(purpose, LlmMessageMapper.toLangChain(messages));
        long perAttemptEstimate = requestPolicy.estimate(purpose, safeMessages, budgetPressure);
        long reserved = scaleEstimate(perAttemptEstimate, maxAttempts);
        boolean background = purpose == Purpose.SUMMARY || purpose == Purpose.EXTRACT;
        LlmBudgetGuard.Reservation reservation = budgetGuard.reserve(traceId, reserved, background);
        boolean acquired = false;
        java.util.concurrent.atomic.AtomicBoolean settled = new java.util.concurrent.atomic.AtomicBoolean();
        LlmJsonExecutor.Accounting accounting = new LlmJsonExecutor.Accounting() {
            @Override
            public void settle(long actualTokens) {
                if (settled.compareAndSet(false, true)) {
                    settleAndRelease(reservation, actualTokens, true);
                }
            }

            @Override
            public void record(String traceId, Purpose purpose, String model,
                               long inputTokens, long outputTokens, long durationMs, String status) {
                recordUsage(traceId, purpose, model, inputTokens, outputTokens, durationMs, status);
            }

            @Override
            public void calibrate(long estimated, long measured) {
                requestPolicy.calibrate(estimated, measured);
            }
        };
        try {
            concurrency.acquire();
            acquired = true;
            Duration timeout = requestTimeout != null ? requestTimeout
                    : Duration.ofSeconds(requestPolicy.timeoutSeconds(purpose));
            return jsonExecutor.execute(purpose, safeMessages, traceId, timeout, maxAttempts,
                    perAttemptEstimate, reserved, budgetPressure, accounting);
        } catch (RuntimeException error) {
            if (acquired) {
                accounting.settle(0);
            } else {
                settleAndRelease(reservation, 0, false);
            }
            throw error;
        }
    }

    /**
     * 多跳证据充分性判断 (Phase 2 V4 2.1): 给 query+已累积证据, 输出是否充分 + 缺口驱动改写。
     * 用于 AgenticRetriever 多跳循环的跳出条件; 失败抛由调用方降级为单跳。
     */
    public String judgeSufficient(String query, List<String> evidenceNodeIds, String traceId) {
        return structuredJudge(judgePromptFactory.forNodeIds(query, evidenceNodeIds), traceId);
    }

    /**
     * Judge 接收有界的证据摘要，而不仅是不透明的节点 ID。刻意不发送完整分块，
     * 以限制提示词成本和意外数据暴露，同时提供覆盖判断所需的文本和图路径。
     */
    public String judgeSufficientWithEvidence(String query, List<Evidence> evidence, String traceId) {
        return structuredJudge(judgePromptFactory.forEvidence(query, evidence), traceId);
    }

    /**
     * Judge 同时接收原始问题和当前子问题，避免多跳改写后只关注局部目标而丢失总体约束。
     */
    public String judgeSufficientWithEvidence(String originalQuery, String currentSubQuery,
                                              List<Evidence> evidence, String traceId) {
        return structuredJudge(
                judgePromptFactory.forEvidence(originalQuery, currentSubQuery, evidence), traceId);
    }

    private String structuredJudge(List<LlmMessage> messages, String traceId) {
        StructuredOutputResult<RetrievalJudgeOutput> result = structuredOutputService.generate(
                StructuredTask.RETRIEVAL_JUDGE,
                Purpose.JUDGE,
                messages,
                RetrievalJudgeOutput.class,
                output -> {
                    if (output.sufficient()
                            && output.followupQuery() != null
                            && !output.followupQuery().isBlank()) {
                        throw new IllegalArgumentException(
                                "sufficient judge result must not contain followup_query");
                    }
                    if (!output.sufficient()
                            && (isBlank(output.followupQuery()) && isBlank(output.missing()))) {
                        throw new IllegalArgumentException(
                                "insufficient judge result must contain a gap");
                    }
                },
                traceId
        );
        if (!result.success()) {
            throw new IllegalStateException("structured retrieval judge output invalid");
        }
        try {
            return mapper.writeValueAsString(result.value());
        } catch (Exception error) {
            throw new IllegalStateException("structured retrieval judge serialization failed", error);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 重排序 (SiliconFlow bge-reranker-v2-m3)。返回与docs等长的相关性分数数组。
     * 失败抛出由调用方降级 (降级矩阵: 重排失败→保持融合排序)。
     */
    public double[] rerank(String query, List<String> docs, String traceId) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("rerank query must not be blank");
        if (docs == null || docs.isEmpty()) return new double[0];
        int rerankLimit = requestPolicy.tokenLimits().rerankInputTokens();
        String safeQuery = requestPolicy.boundedText(query, Math.max(1, rerankLimit / 4));
        int perDocumentLimit = Math.max(1, (rerankLimit - tokenBudget.count(safeQuery)) / Math.max(1, docs.size()));
        List<String> safeDocs = docs.stream()
                .map(doc -> requestPolicy.boundedText(doc == null ? "" : doc, perDocumentLimit))
                .toList();
        long reserved = requestPolicy.estimateRerank(safeQuery, safeDocs);
        LlmBudgetGuard.Reservation reservation = budgetGuard.reserve(traceId, reserved, false);
        boolean acquired = false;
        long t0 = System.currentTimeMillis();
        long actual = 0;
        try {
            concurrency.acquire();
            acquired = true;
            double[] scores = rerankClient.rerank(safeQuery, safeDocs);
            actual = (safeQuery.length() + safeDocs.stream().mapToInt(String::length).sum()) / 2;
            recordUsage(traceId, Purpose.RERANK, "bge-reranker-v2-m3",
                    actual, 0,
                    System.currentTimeMillis() - t0, "ok");
            return scores;
        } catch (Exception e) {
            recordUsage(traceId, Purpose.RERANK, "bge-reranker-v2-m3", 0, 0,
                    System.currentTimeMillis() - t0, "error");
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        } finally {
            settleAndRelease(reservation, actual, acquired);
        }
    }

    private void settleAndRelease(LlmBudgetGuard.Reservation reservation, long actual, boolean acquired) {
        try {
            budgetGuard.settle(reservation, actual);
        } finally {
            if (acquired) concurrency.release();
        }
    }

    private static long scaleEstimate(long estimate, int attempts) {
        long safeEstimate = Math.max(1, estimate);
        if (attempts <= 1) return safeEstimate;
        return safeEstimate > Long.MAX_VALUE / attempts
                ? Long.MAX_VALUE : safeEstimate * attempts;
    }

    private static long cappedAdd(long left, long right, long cap) {
        long safeLeft = Math.max(0, left);
        long safeRight = Math.max(0, right);
        if (safeLeft >= cap || safeRight > cap - safeLeft) return cap;
        return safeLeft + safeRight;
    }

    /** Package-visible compatibility seam for existing request-budget regression tests. */
    List<ChatMessage> boundedMessages(Purpose purpose, List<ChatMessage> messages) {
        return requestPolicy.boundedMessages(purpose, messages, budgetPressure);
    }

    private void recordUsage(String traceId, Purpose purpose, String model,
                             long in, long out, long ms, String status) {
        usageRecorder.record(traceId, purpose, model, in, out, ms, status);
        telemetry.recordCall(traceId, purpose, model, in, out, ms, status);
    }

    private static String safeErrorMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "";
        String oneLine = message.replaceAll("[\\r\\n\\t]", " ").trim();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "…";
    }
}
