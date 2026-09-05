package com.tutor.platform.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 由应用托管、具有有界准入能力的虚拟线程执行器。 */
public final class BoundedVirtualThreadExecutor implements Executor, AutoCloseable {
    private final ThreadPoolExecutor delegate;
    private final String name;

    public BoundedVirtualThreadExecutor(String name, int concurrency, int queueCapacity, MeterRegistry registry) {
        this(name, concurrency, queueCapacity, registry, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public BoundedVirtualThreadExecutor(String name, int concurrency, int queueCapacity, MeterRegistry registry,
                                        RejectedExecutionHandler rejectionHandler) {
        this.name = name;
        int workers = Math.max(1, concurrency);
        int capacity = Math.max(workers, queueCapacity);
        ThreadFactory factory = Thread.ofVirtual().name(name + "-", 0).factory();
        this.delegate = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), factory, rejectionHandler);
        Gauge.builder("tutor.executor.active", delegate, ThreadPoolExecutor::getActiveCount)
                .tag("executor", name).register(registry);
        Gauge.builder("tutor.executor.queued", delegate, executor -> executor.getQueue().size())
                .tag("executor", name).register(registry);
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(command);
    }

    @PreDestroy
    @Override
    public void close() {
        ExecutorLifecycle.shutdown(delegate, name, LoggerFactory.getLogger(BoundedVirtualThreadExecutor.class));
    }
}
