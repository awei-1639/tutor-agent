package com.tutor.agent.expert;

import com.tutor.platform.config.ExecutorLifecycle;
import com.tutor.contract.CancellationToken;
import com.tutor.contract.ExpertOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** Owns expert task execution, cancellation, deadlines, and stage notifications. */
final class ExpertTaskExecutor {
    private static final Logger log = LoggerFactory.getLogger(ExpertTaskExecutor.class);
    private final int expertTimeoutSeconds;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor();

    ExpertTaskExecutor(int expertTimeoutSeconds) {
        this.expertTimeoutSeconds = expertTimeoutSeconds;
    }

    @FunctionalInterface
    interface Invocation {
        ExpertOutput invoke(String name, String briefing, String traceId,
                            Duration timeout, Set<String> availableCitationIds);
    }

    void shutdown() {
        ExecutorLifecycle.shutdown(timeoutScheduler, "expert-timeout", log);
        ExecutorLifecycle.shutdown(executor, "expert-runner", log);
    }

    CompletableFuture<ExpertOutput> submit(
            String name,
            String briefing,
            String traceId,
            Consumer<ExpertRunner.ExpertStage> onExpertDone,
            CancellationToken cancellation,
            long batchDeadlineNanos,
            Set<String> availableCitationIds,
            Invocation invocation
    ) {
        if (cancellation.isCancelled()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<ExpertOutput> future = new CompletableFuture<>();
        Future<?> task;

        try {
            task = executor.submit(() -> {
                try {
                    long remainingNanos = batchDeadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        throw new TimeoutException("专家批次 deadline 超时");
                    }
                    future.complete(invocation.invoke(name, briefing, traceId,
                            Duration.ofNanos(Math.max(1, remainingNanos)), availableCitationIds));
                } catch (Exception error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn(
                    "专家任务被拒绝 expert={} trace={}",
                    name,
                    traceId,
                    e
            );

            notifyExpertDone(onExpertDone, new ExpertRunner.ExpertStage(name, "rejected", "专家任务线程池已关闭"), traceId);
            return CompletableFuture.completedFuture(null);
        }

        java.util.concurrent.atomic.AtomicReference<AutoCloseable> cancellationRegistration =
                new java.util.concurrent.atomic.AtomicReference<>();
        try {
            cancellationRegistration.set(cancellation.onCancel(() -> {
                if (future.completeExceptionally(new CancellationException("请求已取消"))) {
                    task.cancel(true);
                }
            }));
        } catch (RuntimeException e) {
            task.cancel(true);
            future.completeExceptionally(e);
            cancellationRegistration.set(() -> { });
        }

        ScheduledFuture<?> timeout;
        try {
            timeout = timeoutScheduler.schedule(() -> {
                if (future.completeExceptionally(new TimeoutException("专家 deadline 超时"))) {
                    task.cancel(true);
                }
            }, Math.max(1, batchDeadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            task.cancel(true);
            future.completeExceptionally(e);
            timeout = null;
        }

        ScheduledFuture<?> deadline = timeout;
        java.util.concurrent.atomic.AtomicReference<ExpertRunner.ExpertStage> stage =
                new java.util.concurrent.atomic.AtomicReference<>(new ExpertRunner.ExpertStage(name, "failed", "专家未返回结果"));
        return future
                .whenComplete((result, error) -> {
                    if (deadline != null) {
                        deadline.cancel(false);
                    }
                    try {
                        AutoCloseable registration = cancellationRegistration.get();
                        if (registration != null) {
                            registration.close();
                        }
                    } catch (Exception ignored) {
                        // 清理钩子采用尽力而为策略。
                    }
                })
                .handle((result, error) -> {
                    if (error == null) {
                        stage.set(new ExpertRunner.ExpertStage(name, "success", ""));
                        return result;
                    }

                    Throwable cause = unwrap(error);
                    logExpertFailure(name, traceId, error);
                    stage.set(new ExpertRunner.ExpertStage(name, stageStatus(cause), publicStageDetail(cause)));
                    return null;
                })
                // 超时回调不能在调度线程上执行 SSE 或网络工作。
                .whenCompleteAsync((result, error) -> notifyExpertDone(onExpertDone, stage.get(), traceId), executor);
    }
    //安全通知
    private void notifyExpertDone(
            Consumer<ExpertRunner.ExpertStage> callback,
            ExpertRunner.ExpertStage stage,
            String traceId
    ) {
        if (callback == null) {
            return;
        }

        try {
            callback.accept(stage);
        } catch (Exception e) {
            log.warn(
                    "专家完成通知失败 expert={} trace={}",
                    stage.expert(),
                    traceId,
                    e
            );
        }
    }
    //异常记录
    private void logExpertFailure(
            String name,
            String traceId,
            Throwable error
    ) {
        Throwable cause = unwrap(error);

        if (cause instanceof CancellationException) {
            return;
        }

        if (cause instanceof TimeoutException) {
            log.warn(
                    "专家执行超时 expert={} trace={} timeout={}s",
                    name,
                    traceId,
                    expertTimeoutSeconds
            );
            return;
        }

        log.warn("专家执行失败 expert={} trace={} type={} detail={}",
                name, traceId, cause.getClass().getSimpleName(), safeErrorMessage(cause));
    }

    private String stageStatus(Throwable cause) {
        if (cause instanceof CancellationException) return "cancelled";
        if (cause instanceof TimeoutException) return "timeout";
        return "failed";
    }

    private String publicStageDetail(Throwable cause) {
        if (cause instanceof TimeoutException) return "专家执行超时";
        if (cause instanceof CancellationException) return "请求已取消";
        return "专家执行失败";
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;

        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }

        return current;
    }

    private String safeErrorMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "";
        String oneLine = message.replaceAll("[\\r\\n\\t]", " ").trim();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "…";
    }
}
