package com.tutor.agent.tool;

import jakarta.annotation.PreDestroy;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs a registered tool with its declared timeout and owns the execution pool lifecycle. */
final class ToolInvocationRunner {
    private final ExecutorService executor;

    ToolInvocationRunner() {
        this(Executors.newVirtualThreadPerTaskExecutor());
    }

    ToolInvocationRunner(ExecutorService executor) {
        this.executor = executor;
    }

    Object run(ToolRegistration registration, Object input, ToolExecutionContext context)
            throws InterruptedException, ExecutionException, TimeoutException {
        Future<Object> task = executor.submit(() -> registration.handler().execute(input, context));
        try {
            return task.get(registration.spec().timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            task.cancel(true);
            throw timeout;
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
