package com.tutor.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.config.ExecutorLifecycle;
import com.tutor.conversation.context.TokenBudget;
import com.tutor.contract.CancellationToken;
import com.tutor.contract.Purpose;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.output.TokenUsage;

/** Owns the provider HTTP/SSE lifecycle for cancellable streaming generation. */
final class LlmStreamingExecutor {
    private static final Logger log = LoggerFactory.getLogger(LlmStreamingExecutor.class);

    private final ChatStreamProviderClient providerClient;
    private final TokenBudget tokenBudget;
    private final LlmRequestPolicy requestPolicy;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    LlmStreamingExecutor(ChatStreamProviderClient providerClient,
                         TokenBudget tokenBudget,
                         LlmRequestPolicy requestPolicy) {
        this.providerClient = providerClient;
        this.tokenBudget = tokenBudget;
        this.requestPolicy = requestPolicy;
    }

    interface Accounting {
        void settle(long actualTokens);

        void record(String traceId, Purpose purpose, String model,
                    long inputTokens, long outputTokens, long durationMs, String status);

        void calibrate(long estimated, long measured);
    }

    void stream(Purpose purpose, List<ChatMessage> messages, String model, String traceId,
                LlmStreamHandler handler, CancellationToken cancellation, int maxOutputTokens,
                long reservedEstimate, Accounting accounting) {
        AtomicBoolean finished = new AtomicBoolean();
        AtomicLong actualTokens = new AtomicLong();
        AtomicBoolean truncated = new AtomicBoolean();
        AtomicReference<InputStream> inputStream = new AtomicReference<>();
        AtomicReference<CompletableFuture<HttpResponse<InputStream>>> providerRequest =
                new AtomicReference<>();
        AtomicReference<Future<?>> streamTask = new AtomicReference<>();
        AtomicReference<AutoCloseable> cancellationRegistration = new AtomicReference<>();
        Runnable finish = () -> {
            if (finished.compareAndSet(false, true)) {
                accounting.settle(actualTokens.get());
                closeCancellationRegistration(cancellationRegistration);
            }
        };

        try {
            HttpRequest request = providerClient.buildRequest(model, messages, maxOutputTokens);
            Future<?> task = executor.submit(() -> runStreamingRequest(
                    request, purpose, model, traceId, handler, cancellation,
                    inputStream, providerRequest, finish, maxOutputTokens,
                    actualTokens, truncated, reservedEstimate, accounting));
            streamTask.set(task);
            cancellationRegistration.set(cancellation.onCancel(() -> {
                Future<?> running = streamTask.get();
                if (running != null) running.cancel(true);
                CompletableFuture<?> pendingRequest = providerRequest.get();
                if (pendingRequest != null) pendingRequest.cancel(true);
                InputStream input = inputStream.get();
                if (input != null) {
                    try {
                        input.close();
                    } catch (IOException ignored) {
                        // Best-effort close during cancellation.
                    }
                }
                finish.run();
            }));
            if (cancellation.isCancelled()) {
                task.cancel(true);
                finish.run();
            }
            await(task, traceId);
            if (finished.get()) closeCancellationRegistration(cancellationRegistration);
        } catch (RuntimeException error) {
            finish.run();
            throw error;
        }
    }

    void shutdown() {
        ExecutorLifecycle.shutdown(executor, "llm-streaming", log);
    }

    private void await(Future<?> task, String traceId) {
        try {
            task.get();
        } catch (CancellationException error) {
            log.debug("chat stream cancelled trace={}", traceId);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            log.debug("chat stream interrupted trace={}", traceId);
        } catch (ExecutionException error) {
            log.error("chat stream task failed unexpectedly trace={}", traceId, error.getCause());
        }
    }

    private void runStreamingRequest(HttpRequest request, Purpose purpose, String model, String traceId,
                                     LlmStreamHandler handler, CancellationToken cancellation,
                                     AtomicReference<InputStream> inputStream,
                                     AtomicReference<CompletableFuture<HttpResponse<InputStream>>> providerRequest,
                                     Runnable finish, int maxOutputTokens,
                                     AtomicLong actualTokens, AtomicBoolean truncated,
                                     long reservedEstimate, Accounting accounting) {
        long startedAt = System.currentTimeMillis();
        StringBuilder fullText = new StringBuilder();
        AtomicLong inputTokens = new AtomicLong(-1);
        AtomicLong outputTokens = new AtomicLong(-1);
        AtomicBoolean usageRecorded = new AtomicBoolean();
        try {
            CompletableFuture<HttpResponse<InputStream>> requestFuture =
                    httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            providerRequest.set(requestFuture);
            if (cancellation.isCancelled()) {
                requestFuture.cancel(true);
                return;
            }
            HttpResponse<InputStream> response = requestFuture.get();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (InputStream ignored = response.body()) {
                    // Consume and close the provider error response.
                }
                throw new IllegalStateException("chat stream HTTP " + response.statusCode());
            }
            inputStream.set(response.body());
            try (InputStream body = response.body()) {
                streamSse(body, fullText, inputTokens, outputTokens, actualTokens,
                        truncated, handler, cancellation, maxOutputTokens);
            } finally {
                inputStream.set(null);
            }
            if (cancellation.isCancelled()) {
                if (outputTokens.get() < 0) outputTokens.set(tokenBudget.count(fullText.toString()));
                recordStreamUsage(accounting, traceId, purpose, model, inputTokens, outputTokens,
                        startedAt, "cancelled", usageRecorded);
                finish.run();
                return;
            }
            if (fullText.toString().isBlank()) {
                throw new IllegalStateException("chat stream returned an empty response");
            }
            if (outputTokens.get() < 0) outputTokens.set(tokenBudget.count(fullText.toString()));
            TokenUsage usage = tokenUsage(inputTokens.get(), outputTokens.get());
            actualTokensFor(usage, inputTokens, outputTokens);
            recordStreamUsage(accounting, traceId, purpose, model, inputTokens, outputTokens,
                    startedAt, "ok", usageRecorded);
            if (inputTokens.get() > 0 || outputTokens.get() > 0) {
                accounting.calibrate(reservedEstimate,
                        Math.max(0, inputTokens.get()) + Math.max(0, outputTokens.get()));
            }
            finish.run();
            if (!cancellation.isCancelled()) {
                handler.onComplete(new LlmStreamResult(
                        model, inputTokens.get(), outputTokens.get(), truncated.get()));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            if (outputTokens.get() < 0) outputTokens.set(tokenBudget.count(fullText.toString()));
            recordStreamUsage(accounting, traceId, purpose, model, inputTokens, outputTokens,
                    startedAt, cancellation.isCancelled() ? "cancelled" : "error", usageRecorded);
            finish.run();
            if (!cancellation.isCancelled()) handler.onError(error);
        } catch (Exception error) {
            if (outputTokens.get() < 0) outputTokens.set(tokenBudget.count(fullText.toString()));
            recordStreamUsage(accounting, traceId, purpose, model, inputTokens, outputTokens,
                    startedAt, cancellation.isCancelled() ? "cancelled" : "error", usageRecorded);
            finish.run();
            if (!cancellation.isCancelled()) {
                Throwable reported = error instanceof ExecutionException execution
                        && execution.getCause() != null ? execution.getCause() : error;
                handler.onError(reported);
            }
        }
    }

    private void streamSse(InputStream body, StringBuilder fullText,
                           AtomicLong inputTokens, AtomicLong outputTokens,
                           AtomicLong actualTokens, AtomicBoolean truncated,
                           LlmStreamHandler handler, CancellationToken cancellation,
                           int maxOutputTokens) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            StringBuilder data = new StringBuilder();
            while (!cancellation.isCancelled() && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (consumeSseData(data.toString(), fullText, inputTokens, outputTokens,
                            actualTokens, truncated, handler, cancellation, maxOutputTokens)) return;
                    data.setLength(0);
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).stripLeading());
                }
            }
            if (!cancellation.isCancelled() && data.length() > 0) {
                consumeSseData(data.toString(), fullText, inputTokens, outputTokens,
                        actualTokens, truncated, handler, cancellation, maxOutputTokens);
            }
        }
    }

    private boolean consumeSseData(String data, StringBuilder fullText,
                                   AtomicLong inputTokens, AtomicLong outputTokens,
                                   AtomicLong actualTokens, AtomicBoolean truncated,
                                   LlmStreamHandler handler, CancellationToken cancellation,
                                   int maxOutputTokens) throws IOException {
        if (data == null || data.isBlank()) return false;
        if ("[DONE]".equals(data.trim())) return true;
        JsonNode root = mapper.readTree(data);
        JsonNode usage = root.path("usage");
        if (usage.isObject()) {
            inputTokens.set(usage.path("prompt_tokens").asLong(usage.path("input_tokens").asLong(-1)));
            outputTokens.set(usage.path("completion_tokens").asLong(usage.path("output_tokens").asLong(-1)));
            if (inputTokens.get() > 0 || outputTokens.get() > 0) {
                actualTokens.set(Math.max(0, inputTokens.get()) + Math.max(0, outputTokens.get()));
            }
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty() || cancellation.isCancelled()) return false;
        JsonNode choice = choices.get(0);
        if ("length".equals(choice.path("finish_reason").asText(null))) truncated.set(true);
        JsonNode delta = choice.path("delta");
        String token = delta.path("content").asText("");
        if (!token.isEmpty()) {
            int remaining = maxOutputTokens - tokenBudget.count(fullText.toString());
            if (remaining <= 0) {
                truncated.set(true);
                return true;
            }
            String boundedToken = tokenBudget.truncate(token, remaining);
            if (!boundedToken.equals(token) && boundedToken.endsWith("…")) {
                boundedToken = boundedToken.substring(0, boundedToken.length() - 1);
            }
            if (boundedToken.isEmpty()) {
                truncated.set(true);
                return true;
            }
            fullText.append(boundedToken);
            handler.onToken(boundedToken);
            int total = tokenBudget.count(fullText.toString());
            actualTokens.set(total);
            if (total >= maxOutputTokens) {
                truncated.set(true);
                return true;
            }
        }
        return false;
    }

    private void recordStreamUsage(Accounting accounting, String traceId, Purpose purpose, String model,
                                   AtomicLong input, AtomicLong output, long startedAt,
                                   String status, AtomicBoolean recorded) {
        if (!recorded.compareAndSet(false, true)) return;
        long in = Math.max(0, input.get());
        long out = Math.max(0, output.get());
        actualTokensFor(new TokenUsage((int) in, (int) out), input, output);
        accounting.record(traceId, purpose, model, in, out,
                System.currentTimeMillis() - startedAt, status);
    }

    private TokenUsage tokenUsage(long input, long output) {
        if (input < 0 && output < 0) return null;
        return new TokenUsage(input < 0 ? null : (int) input,
                output < 0 ? null : (int) output);
    }

    private void actualTokensFor(TokenUsage usage, AtomicLong input, AtomicLong output) {
        if (usage == null) return;
        input.set(usage.inputTokenCount() == null ? 0 : usage.inputTokenCount());
        output.set(usage.outputTokenCount() == null ? 0 : usage.outputTokenCount());
    }

    private void closeCancellationRegistration(AtomicReference<AutoCloseable> registrationRef) {
        AutoCloseable registration = registrationRef.getAndSet(null);
        if (registration == null) return;
        try {
            registration.close();
        } catch (Exception ignored) {
            // Cancellation cleanup is best effort.
        }
    }
}
