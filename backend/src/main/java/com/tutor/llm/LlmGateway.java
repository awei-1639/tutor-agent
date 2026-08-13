package com.tutor.llm;

import com.tutor.config.LlmProperties;
import com.tutor.config.ExecutorLifecycle;
import com.tutor.context.TokenBudget;
import com.tutor.contract.CancellationToken;
import com.tutor.contract.Purpose;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.TokenUsage;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;

/**
 * 全项目唯一的 LLM/Embedding 出口 (实现设计 6.1)。
 * 职责: purpose→model 路由、分级超时、轻量重试(Spike1结论)、日限额、llm_usage 记账。
 */
@Component
public class LlmGateway {
    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);
    private static final int EMBED_MAX_ATTEMPTS = 2;
    private final LlmProperties props;
    private final JdbcTemplate jdbc;
    private final LlmBudgetGuard budgetGuard;
    private final LlmConcurrencyGate concurrency;
    private final EmbeddingModel embeddingModel;
    private final TokenBudget tokenBudget = new TokenBudget();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService streamingExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public LlmGateway(LlmProperties props, JdbcTemplate jdbc, LlmBudgetGuard budgetGuard,
                      LlmConcurrencyGate concurrency) {
        this.props = props;
        this.jdbc = jdbc;
        this.budgetGuard = budgetGuard;
        this.concurrency = concurrency;
        this.embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(props.siliconflow().apiKey())
                .baseUrl(props.siliconflow().baseUrl())
                .modelName(props.routing().get("embed"))
                .timeout(Duration.ofSeconds(30))
                // The gateway owns retries so budget accounting cannot be
                // multiplied by an implicit SDK retry loop.
                .maxRetries(0)
                .build();
    }

    @PreDestroy
    void shutdownStreamingExecutor() {
        ExecutorLifecycle.shutdown(streamingExecutor, "llm-streaming", log);
    }

    /** 查询/文档向量化。失败重试1次 (429/5xx/超时同一策略, Spike1结论: 轻量即可)。 */
    public float[] embed(String text, String traceId) {
        String safeText = boundedText(text, tokenLimits().embedInputTokens());
        long perAttemptEstimate = estimate(safeText);
        long reserved = scaleEstimate(perAttemptEstimate, EMBED_MAX_ATTEMPTS);
        budgetGuard.reserve(traceId, reserved);
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
                    Embedding e = embeddingModel.embed(safeText).content();
                    actual = cappedAdd(failedEstimate, tokenBudget.count(safeText), reserved);
                    recordUsage(traceId, Purpose.EMBED, props.routing().get("embed"),
                            actual, 0, System.currentTimeMillis() - t0, "ok");
                    return e.vector();
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
            settleAndRelease(traceId, reserved, actual, acquired);
        }
    }

    /** 流式对话。首 token 前失败可由调用方决定是否重建; 网关负责限额检查与记账。 */
    public void chatStream(Purpose purpose, List<ChatMessage> messages, String traceId,
                           StreamingChatResponseHandler handler) {
        chatStream(purpose, messages, traceId, handler, new CancellationToken());
    }

    /**
     * Cancellable streaming chat. The cancellation token is wired to the provider's
     * ResponseHandle, so disconnecting an SSE client closes the underlying HTTP stream.
     */
    public void chatStream(Purpose purpose, List<ChatMessage> messages, String traceId,
                           StreamingChatResponseHandler handler, CancellationToken cancellation) {
        if (cancellation == null) throw new IllegalArgumentException("cancellation must not be null");
        if (cancellation.isCancelled()) return;
        List<ChatMessage> safeMessages = boundedMessages(purpose, messages);
        long reserved = estimate(purpose, safeMessages);
        budgetGuard.reserve(traceId, reserved);
        boolean acquired = false;
        AtomicBoolean finished = new AtomicBoolean();
        AtomicLong actualTokens = new AtomicLong();
        java.util.concurrent.atomic.AtomicReference<InputStream> inputStream = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Future<?>> streamTask = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<AutoCloseable> cancellationRegistration = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            concurrency.acquire();
            acquired = true;
            if (cancellation.isCancelled()) {
                settleAndRelease(traceId, reserved, 0, true);
                return;
            }
            String model = props.routing().getOrDefault(purpose.name().toLowerCase(), "deepseek-chat");
            Runnable finish = () -> {
                if (finished.compareAndSet(false, true)) {
                    settleAndRelease(traceId, reserved, actualTokens.get(), true);
                    closeCancellationRegistration(cancellationRegistration);
                }
            };
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(streamEndpoint()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + props.deepseek().apiKey())
                    .timeout(Duration.ofSeconds(props.timeout().chatSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(streamBody(model, safeMessages, outputLimit(purpose)), StandardCharsets.UTF_8))
                    .build();
            Future<?> task = streamingExecutor.submit(() -> runStreamingRequest(
                    request, purpose, model, traceId, handler, cancellation, inputStream, finish,
                    outputLimit(purpose)));
            streamTask.set(task);
            cancellationRegistration.set(cancellation.onCancel(() -> {
                Future<?> running = streamTask.get();
                if (running != null) running.cancel(true);
                InputStream input = inputStream.get();
                if (input != null) {
                    try {
                        input.close();
                    } catch (IOException ignored) {
                        // Closing an already closed stream is harmless.
                    }
                }
                finish.run();
            }));
            if (finished.get()) {
                closeCancellationRegistration(cancellationRegistration);
            }
            if (cancellation.isCancelled()) {
                task.cancel(true);
                finish.run();
            }
        } catch (RuntimeException e) {
            if (acquired && finished.compareAndSet(false, true)) {
                settleAndRelease(traceId, reserved, actualTokens.get(), true);
                closeCancellationRegistration(cancellationRegistration);
            } else if (!acquired) {
                settleAndRelease(traceId, reserved, 0, false);
            }
            throw e;
        }
    }

    private void runStreamingRequest(HttpRequest request, Purpose purpose, String model, String traceId,
                                     StreamingChatResponseHandler handler, CancellationToken cancellation,
                                     java.util.concurrent.atomic.AtomicReference<InputStream> inputStream,
                                     Runnable finish, int maxOutputTokens) {
        long startedAt = System.currentTimeMillis();
        StringBuilder fullText = new StringBuilder();
        AtomicLong inputTokens = new AtomicLong(-1);
        AtomicLong outputTokens = new AtomicLong(-1);
        AtomicBoolean usageRecorded = new AtomicBoolean();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (InputStream ignored = response.body()) {
                    // Drain/close the error body before reporting the provider failure.
                }
                throw new IllegalStateException("chat stream HTTP " + response.statusCode());
            }
            inputStream.set(response.body());
            try (InputStream body = response.body()) {
                streamSse(body, fullText, inputTokens, outputTokens, handler, cancellation, maxOutputTokens);
            } finally {
                inputStream.set(null);
            }
            if (cancellation.isCancelled()) {
                if (outputTokens.get() < 0) outputTokens.set(tokenBudget.count(fullText.toString()));
                recordStreamUsage(traceId, purpose, model, inputTokens, outputTokens,
                        startedAt, "cancelled", usageRecorded);
                finish.run();
                return;
            }
            if (fullText.toString().isBlank()) {
                throw new IllegalStateException("chat stream returned an empty response");
            }
            if (outputTokens.get() < 0) outputTokens.set(tokenBudget.count(fullText.toString()));
            TokenUsage usage = tokenUsage(inputTokens.get(), outputTokens.get());
            ChatResponse complete = ChatResponse.builder()
                    .aiMessage(AiMessage.from(fullText.toString()))
                    .modelName(model)
                    .tokenUsage(usage)
                    .build();
            actualTokensFor(usage, inputTokens, outputTokens);
            recordStreamUsage(traceId, purpose, model, inputTokens, outputTokens,
                    startedAt, "ok", usageRecorded);
            finish.run();
            if (!cancellation.isCancelled()) handler.onCompleteResponse(complete);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (outputTokens.get() < 0) outputTokens.set(tokenBudget.count(fullText.toString()));
            recordStreamUsage(traceId, purpose, model, inputTokens, outputTokens,
                    startedAt, cancellation.isCancelled() ? "cancelled" : "error", usageRecorded);
            finish.run();
            if (!cancellation.isCancelled()) handler.onError(e);
        } catch (Exception e) {
            if (outputTokens.get() < 0) outputTokens.set(tokenBudget.count(fullText.toString()));
            recordStreamUsage(traceId, purpose, model, inputTokens, outputTokens,
                    startedAt, cancellation.isCancelled() ? "cancelled" : "error", usageRecorded);
            finish.run();
            if (!cancellation.isCancelled()) handler.onError(e);
        }
    }

    private void streamSse(InputStream body, StringBuilder fullText,
                           AtomicLong inputTokens, AtomicLong outputTokens,
                           StreamingChatResponseHandler handler, CancellationToken cancellation,
                           int maxOutputTokens) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            StringBuilder data = new StringBuilder();
            while (!cancellation.isCancelled() && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (consumeSseData(data.toString(), fullText, inputTokens, outputTokens, handler, cancellation,
                            maxOutputTokens)) return;
                    data.setLength(0);
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).stripLeading());
                }
            }
            if (!cancellation.isCancelled() && data.length() > 0) {
                consumeSseData(data.toString(), fullText, inputTokens, outputTokens, handler, cancellation,
                        maxOutputTokens);
            }
        }
    }

    private boolean consumeSseData(String data, StringBuilder fullText,
                                   AtomicLong inputTokens, AtomicLong outputTokens,
                                   StreamingChatResponseHandler handler, CancellationToken cancellation,
                                   int maxOutputTokens) throws IOException {
        if (data == null || data.isBlank()) return false;
        if ("[DONE]".equals(data.trim())) return true;
        JsonNode root = mapper.readTree(data);
        JsonNode usage = root.path("usage");
        if (usage.isObject()) {
            inputTokens.set(usage.path("prompt_tokens").asLong(usage.path("input_tokens").asLong(-1)));
            outputTokens.set(usage.path("completion_tokens").asLong(usage.path("output_tokens").asLong(-1)));
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty() || cancellation.isCancelled()) return false;
        JsonNode delta = choices.get(0).path("delta");
        String token = delta.path("content").asText("");
        if (!token.isEmpty()) {
            int remaining = maxOutputTokens - tokenBudget.count(fullText.toString());
            if (remaining <= 0) return true;
            String boundedToken = tokenBudget.truncate(token, remaining);
            // truncate() adds an ellipsis for human-facing text; a stream delta
            // must not invent one, so remove that marker when the hard cap cuts
            // a provider chunk.
            if (!boundedToken.equals(token) && boundedToken.endsWith("…")) {
                boundedToken = boundedToken.substring(0, boundedToken.length() - 1);
            }
            if (boundedToken.isEmpty()) return true;
            fullText.append(boundedToken);
            handler.onPartialResponse(boundedToken);
            if (tokenBudget.count(fullText.toString()) >= maxOutputTokens) return true;
        }
        return false;
    }

    private TokenUsage tokenUsage(long input, long output) {
        if (input < 0 && output < 0) return null;
        return new TokenUsage(input < 0 ? null : (int) input, output < 0 ? null : (int) output);
    }

    private void actualTokensFor(TokenUsage usage, AtomicLong input, AtomicLong output) {
        if (usage == null) return;
        input.set(usage.inputTokenCount() == null ? 0 : usage.inputTokenCount());
        output.set(usage.outputTokenCount() == null ? 0 : usage.outputTokenCount());
    }

    private void recordStreamUsage(String traceId, Purpose purpose, String model,
                                   AtomicLong input, AtomicLong output, long startedAt,
                                   String status, AtomicBoolean recorded) {
        if (!recorded.compareAndSet(false, true)) return;
        long in = Math.max(0, input.get());
        long out = Math.max(0, output.get());
        actualTokensFor(new TokenUsage((int) in, (int) out), input, output);
        recordUsage(traceId, purpose, model, in, out, System.currentTimeMillis() - startedAt, status);
    }

    private String streamEndpoint() {
        String base = props.deepseek().baseUrl();
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return normalized.endsWith("/v1") ? normalized + "/chat/completions" : normalized + "/v1/chat/completions";
    }

    private String streamBody(String model, List<ChatMessage> messages, int maxOutputTokens) {
        var root = mapper.createObjectNode();
        root.put("model", model);
        root.put("temperature", 0.3);
        root.put("stream", true);
        root.put("max_tokens", maxOutputTokens);
        var array = mapper.createArrayNode();
        for (ChatMessage message : messages) {
            var node = mapper.createObjectNode();
            if (message instanceof SystemMessage system) {
                node.put("role", "system");
                node.put("content", system.text());
            } else if (message instanceof UserMessage user && user.hasSingleText()) {
                node.put("role", "user");
                node.put("content", user.singleText());
            } else if (message instanceof AiMessage ai && ai.text() != null) {
                node.put("role", "assistant");
                node.put("content", ai.text());
            } else {
                throw new IllegalArgumentException("unsupported chat message type: " + message.type());
            }
            array.add(node);
        }
        root.set("messages", array);
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("failed to encode chat request", e);
        }
    }

    private void closeCancellationRegistration(java.util.concurrent.atomic.AtomicReference<AutoCloseable> registrationRef) {
        AutoCloseable registration = registrationRef.getAndSet(null);
        if (registration == null) return;
        try {
            registration.close();
        } catch (Exception ignored) {
            // Cancellation cleanup is best-effort.
        }
    }

    /**
     * 非流式 JSON 调用 (画像抽取/router 等结构化用途)。
     * response_format=json_object + 按用途控制重试次数 (背景/判定节点默认单次，
     * 用户可见的抽取/计划保留一次轻量重试)。
     */
    public String chatJson(Purpose purpose, List<ChatMessage> messages, String traceId) {
        return chatJson(purpose, messages, traceId, null, defaultMaxAttempts(purpose));
    }

    /**
     * Structured call with an optional per-attempt timeout and retry count.
     * Expert fan-out uses a single short attempt so its outer deadline cannot
     * leave a hidden second provider request running after the caller has timed out.
     */
    public String chatJson(Purpose purpose, List<ChatMessage> messages, String traceId,
                           Duration requestTimeout, int maxAttempts) {
        if (maxAttempts < 1 || maxAttempts > 2) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 2");
        }
        List<ChatMessage> safeMessages = boundedMessages(purpose, messages);
        long perAttemptEstimate = estimate(purpose, safeMessages);
        long reserved = scaleEstimate(perAttemptEstimate, maxAttempts);
        budgetGuard.reserve(traceId, reserved);
        boolean acquired = false;
        RuntimeException last = null;
        long actual = 0;
        try {
            concurrency.acquire();
            acquired = true;
            String model = props.routing().getOrDefault(purpose.name().toLowerCase(), "deepseek-chat");
            Duration timeout = requestTimeout != null ? requestTimeout : Duration.ofSeconds(timeoutSeconds(purpose));
            OpenAiChatModel chat = OpenAiChatModel.builder()
                    .apiKey(props.deepseek().apiKey())
                    .baseUrl(props.deepseek().baseUrl())
                    .modelName(model)
                    .temperature(0.0)
                    .responseFormat("json_object")
                    .maxTokens(outputLimit(purpose))
                    // Retry policy is deliberately explicit in the gateway. The
                    // LangChain4j default would otherwise add hidden attempts.
                    .maxRetries(0)
                    .timeout(timeout)
                    .build();
            long t0 = System.currentTimeMillis();
            long failedEstimate = 0;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    ChatResponse resp = chat.chat(safeMessages);
                    TokenUsage u = resp.tokenUsage();
                    long measured = usageInput(u) + usageOutput(u);
                    // Some OpenAI-compatible providers omit usage entirely. Do
                    // not release the whole reservation as if that call were free.
                    if (measured == 0) measured = perAttemptEstimate;
                    actual = cappedAdd(failedEstimate, measured, reserved);
                    recordUsage(traceId, purpose, model,
                            usageInput(u), usageOutput(u),
                            System.currentTimeMillis() - t0, "ok");
                    return resp.aiMessage().text();
                } catch (RuntimeException ex) {
                    last = ex;
                    failedEstimate = cappedAdd(failedEstimate, perAttemptEstimate, reserved);
                    log.warn("chatJson {} attempt {} failed type={} detail={}", purpose, attempt,
                            ex.getClass().getSimpleName(), safeErrorMessage(ex));
                }
            }
            actual = failedEstimate;
            recordUsage(traceId, purpose, model, failedEstimate, 0,
                    System.currentTimeMillis() - t0, "error_estimated");
            throw last;
        } finally {
            settleAndRelease(traceId, reserved, actual, acquired);
        }
    }

    /**
     * 多跳证据充分性判断 (Phase 2 V4 2.1): 给 query+已累积证据, 输出是否充分 + 缺口驱动改写。
     * 用于 AgenticRetriever 多跳循环的跳出条件; 失败抛由调用方降级为单跳。
     */
    public String judgeSufficient(String query, List<String> evidenceNodeIds, String traceId) {
        String prompt = "查询: " + query + "\n已累积证据节点ID: " + evidenceNodeIds
                + "\n请判断这些证据是否足以完整回答查询。输出JSON {sufficient: bool, followup_query: string|null, missing: string|null}";
        return chatJson(Purpose.ROUTER, List.of(
                dev.langchain4j.data.message.SystemMessage.from(
                        "你是多跳检索的证据充分性判断器。判断规则: "
                                + "1) 若证据覆盖了回答问题所需的所有关键概念/前置技能/资源→sufficient=true; "
                                + "2) 否则→sufficient=false, followup_query=针对缺口的更窄查询, missing=缺失的关键概念关键词; "
                                + "3) followup_query 不应与原 query 重复, 应聚焦缺失的具体子概念。"),
                dev.langchain4j.data.message.UserMessage.from(prompt)), traceId);
    }

    /**
     * 重排序 (SiliconFlow bge-reranker-v2-m3)。返回与docs等长的相关性分数数组。
     * 失败抛出由调用方降级 (降级矩阵: 重排失败→保持融合排序)。
     */
    public double[] rerank(String query, List<String> docs, String traceId) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("rerank query must not be blank");
        if (docs == null || docs.isEmpty()) return new double[0];
        int rerankLimit = tokenLimits().rerankInputTokens();
        String safeQuery = boundedText(query, Math.max(1, rerankLimit / 4));
        int perDocumentLimit = Math.max(1, (rerankLimit - tokenBudget.count(safeQuery)) / Math.max(1, docs.size()));
        List<String> safeDocs = docs.stream()
                .map(doc -> boundedText(doc == null ? "" : doc, perDocumentLimit))
                .toList();
        long reserved = estimateRerank(safeQuery, safeDocs);
        budgetGuard.reserve(traceId, reserved);
        boolean acquired = false;
        long t0 = System.currentTimeMillis();
        long actual = 0;
        try {
            concurrency.acquire();
            acquired = true;
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var body = mapper.writeValueAsString(java.util.Map.of(
                    "model", "BAAI/bge-reranker-v2-m3", "query", safeQuery, "documents", safeDocs));
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(props.siliconflow().baseUrl() + "/rerank"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.siliconflow().apiKey())
                    .timeout(Duration.ofSeconds(15))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var resp = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) throw new IllegalStateException("rerank HTTP " + resp.statusCode());
            var root = mapper.readTree(resp.body());
            double[] scores = new double[safeDocs.size()];
            for (var r : root.path("results")) {
                int index = r.path("index").asInt(-1);
                if (index >= 0 && index < scores.length) scores[index] = r.path("relevance_score").asDouble();
            }
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
            settleAndRelease(traceId, reserved, actual, acquired);
        }
    }

    private void settleAndRelease(String traceId, long reserved, long actual, boolean acquired) {
        try {
            budgetGuard.settle(traceId, reserved, actual);
        } finally {
            if (acquired) concurrency.release();
        }
    }

    private static long estimate(String text) {
        return Math.max(128, (text == null ? 0 : text.length() / 2) + 128);
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

    private static long usageInput(TokenUsage usage) {
        return usage == null || usage.inputTokenCount() == null
                ? 0 : Math.max(0, usage.inputTokenCount());
    }

    private static long usageOutput(TokenUsage usage) {
        return usage == null || usage.outputTokenCount() == null
                ? 0 : Math.max(0, usage.outputTokenCount());
    }

    private long estimate(Purpose purpose, List<ChatMessage> messages) {
        int input = messages.stream().mapToInt(message -> tokenBudget.count(messageText(message))).sum();
        return Math.max(256, input + outputLimit(purpose) + 128L);
    }

    private List<ChatMessage> boundedMessages(Purpose purpose, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return List.of();
        List<ChatMessage> safe = messages.stream().filter(Objects::nonNull).toList();
        int max = inputLimit(purpose);
        int total = safe.stream().mapToInt(message -> tokenBudget.count(messageText(message))).sum();
        if (total <= max) return safe;
        if (safe.size() == 1) return List.of(withText(safe.getFirst(), tokenBudget.truncate(messageText(safe.getFirst()), max)));

        int lastIndex = safe.size() - 1;
        boolean hasSystem = safe.getFirst() instanceof SystemMessage;
        // PromptAssembler already enforces a global system-context plan. Preserve it instead of
        // silently discarding retrieved evidence merely because it appears later in the prompt.
        int systemBudget = hasSystem ? Math.min(tokenBudget.count(messageText(safe.getFirst())), Math.max(1, max * 2 / 5)) : 0;
        int finalBudget = hasSystem && lastIndex == 0 ? 0
                : Math.max(1, (int) Math.round(max * finalMessageShare(purpose)));
        finalBudget = Math.min(finalBudget, Math.max(1, max - systemBudget));
        int remaining = Math.max(0, max - systemBudget - finalBudget);
        List<ChatMessage> middle = new ArrayList<>();
        int middleStart = hasSystem ? 1 : 0;
        int middleEnd = lastIndex - (finalBudget > 0 ? 1 : 0);
        for (int i = middleEnd - 1; i >= middleStart && remaining > 0; i--) {
            String text = messageText(safe.get(i));
            int take = Math.min(tokenBudget.count(text), remaining);
            middle.add(0, withText(safe.get(i), tokenBudget.truncate(text, take)));
            remaining -= take;
        }

        List<ChatMessage> result = new ArrayList<>();
        if (hasSystem) {
            result.add(withText(safe.getFirst(), tokenBudget.truncate(messageText(safe.getFirst()), systemBudget)));
        }
        result.addAll(middle);
        if (finalBudget > 0) {
            result.add(withText(safe.getLast(), tokenBudget.truncate(messageText(safe.getLast()), finalBudget)));
        }
        return result;
    }

    private String messageText(ChatMessage message) {
        if (message instanceof SystemMessage system) return system.text();
        if (message instanceof UserMessage user && user.hasSingleText()) return user.singleText();
        if (message instanceof AiMessage ai && ai.text() != null) return ai.text();
        return message.toString();
    }

    private ChatMessage withText(ChatMessage message, String text) {
        if (message instanceof SystemMessage) return SystemMessage.from(text);
        if (message instanceof UserMessage) return UserMessage.from(text);
        if (message instanceof AiMessage) return AiMessage.from(text);
        return message;
    }

    private String boundedText(String text, int maxTokens) {
        if (text == null || text.isBlank()) return "";
        return tokenBudget.truncate(text, maxTokens);
    }

    private LlmProperties.PurposeLimit limitFor(Purpose purpose) {
        LlmProperties.TokenLimits limits = tokenLimits();
        return switch (purpose) {
            case ROUTER -> limits.router();
            case CHAT -> limits.chat();
            case EXPERT -> limits.expert();
            case SUMMARY -> limits.summary();
            case EXTRACT -> limits.extract();
            case JUDGE -> limits.judge();
            case PLAN -> limits.plan();
            default -> limits.chat();
        };
    }

    private LlmProperties.TokenLimits tokenLimits() {
        return props.tokens() == null ? LlmProperties.TokenLimits.defaults() : props.tokens();
    }

    private int inputLimit(Purpose purpose) { return limitFor(purpose).inputTokens(); }

    private int outputLimit(Purpose purpose) { return limitFor(purpose).outputTokens(); }

    private double finalMessageShare(Purpose purpose) {
        return switch (purpose) {
            case CHAT -> 0.55;
            case ROUTER -> 0.65;
            default -> 0.75;
        };
    }

    private int timeoutSeconds(Purpose purpose) {
        return switch (purpose) {
            case ROUTER -> props.timeout().routerSeconds();
            case SUMMARY -> props.timeout().summarySeconds();
            case EXPERT -> props.timeout().expertSeconds();
            default -> props.timeout().chatSeconds();
        };
    }

    private int defaultMaxAttempts(Purpose purpose) {
        return switch (purpose) {
            // These calls are either background work or cheap routing/judging. Retrying
            // them during an outage only increases budget pressure and queue time.
            case ROUTER, EXPERT, SUMMARY, JUDGE -> 1;
            default -> 2;
        };
    }

    private static long estimateRerank(String query, List<String> docs) {
        int queryLength = query == null ? 0 : query.length();
        int documentLength = docs == null ? 0 : docs.stream().filter(java.util.Objects::nonNull)
                .mapToInt(String::length).sum();
        return Math.max(128, (queryLength + documentLength) / 2 + 128);
    }

    private void recordUsage(String traceId, Purpose purpose, String model,
                             long in, long out, long ms, String status) {
        try {
            jdbc.update("INSERT INTO llm_usage (trace_id, purpose, model, tokens_in, tokens_out, duration_ms, status) VALUES (?,?,?,?,?,?,?)",
                    traceId, purpose.name().toLowerCase(), model, in, out, (int) ms, status);
        } catch (Exception e) {
            log.error("llm_usage 记账失败(不阻塞主链路): {}", e.getMessage());
        }
    }

    private static String safeErrorMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "";
        String oneLine = message.replaceAll("[\\r\\n\\t]", " ").trim();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "…";
    }
}
