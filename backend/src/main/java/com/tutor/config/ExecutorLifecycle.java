package com.tutor.config;

import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Graceful, bounded shutdown policy for locally-created asynchronous workloads. */
public final class ExecutorLifecycle {
    private static final long GRACEFUL_SHUTDOWN_SECONDS = 15;

    private ExecutorLifecycle() {
    }

    public static void shutdown(ExecutorService executor, String name, Logger log) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(GRACEFUL_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                log.warn("executor={} did not stop within {}s; interrupting outstanding tasks",
                        name, GRACEFUL_SHUTDOWN_SECONDS);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
