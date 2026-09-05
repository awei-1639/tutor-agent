package com.tutor.platform.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedVirtualThreadExecutorTest {
    @Test
    void limitsConcurrentWorkAndDrainsQueuedWork() throws Exception {
        try (BoundedVirtualThreadExecutor executor = new BoundedVirtualThreadExecutor(
                "test-embedding", 2, 4, new SimpleMeterRegistry())) {
            CountDownLatch started = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch completed = new CountDownLatch(4);
            AtomicInteger running = new AtomicInteger();
            AtomicInteger maximum = new AtomicInteger();

            for (int i = 0; i < 4; i++) {
                executor.execute(() -> {
                    int current = running.incrementAndGet();
                    maximum.accumulateAndGet(current, Math::max);
                    started.countDown();
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        running.decrementAndGet();
                        completed.countDown();
                    }
                });
            }

            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(maximum.get()).isEqualTo(2);
            release.countDown();
            assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }
}
