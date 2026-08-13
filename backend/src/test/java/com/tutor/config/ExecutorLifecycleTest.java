package com.tutor.config;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutorLifecycleTest {

    @Test
    void shutsDownExecutorAndRejectsFurtherWork() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> { });

        ExecutorLifecycle.shutdown(executor, "test", LoggerFactory.getLogger(getClass()));

        assertThat(executor.isShutdown()).isTrue();
        assertThat(executor.isTerminated()).isTrue();
    }
}
