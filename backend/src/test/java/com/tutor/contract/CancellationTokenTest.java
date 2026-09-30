package com.tutor.contract;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CancellationTokenTest {

    @Test
    void reportsCancelledStateAfterCancel() {
        CancellationToken token = new CancellationToken();
        assertThat(token.isCancelled()).isFalse();

        assertThat(token.cancel()).isTrue();

        assertThat(token.isCancelled()).isTrue();
    }

    @Test
    void cancelReturnsTrueOnlyForTheFirstCaller() {
        CancellationToken token = new CancellationToken();

        assertThat(token.cancel()).isTrue();
        assertThat(token.cancel()).isFalse();
    }

    @Test
    void rejectsNullListener() {
        CancellationToken token = new CancellationToken();

        assertThatThrownBy(() -> token.onCancel(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void runsListenerRegisteredBeforeCancelExactlyOnce() {
        CancellationToken token = new CancellationToken();
        AtomicInteger runs = new AtomicInteger();

        token.onCancel(runs::incrementAndGet);
        token.cancel();

        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void runsListenerRegisteredAfterCancelImmediatelyAndOnce() {
        CancellationToken token = new CancellationToken();
        token.cancel();
        AtomicInteger runs = new AtomicInteger();

        token.onCancel(runs::incrementAndGet);

        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void continuesRunningRemainingListenersWhenOneThrows() {
        CancellationToken token = new CancellationToken();
        AtomicInteger runs = new AtomicInteger();

        token.onCancel(() -> { throw new RuntimeException("boom"); });
        token.onCancel(runs::incrementAndGet);

        assertThat(token.cancel()).isTrue();
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void closingRegistrationBeforeCancelPreventsListenerFromRunning() throws Exception {
        CancellationToken token = new CancellationToken();
        AtomicInteger runs = new AtomicInteger();

        AutoCloseable registration = token.onCancel(runs::incrementAndGet);
        registration.close();
        token.cancel();

        assertThat(runs.get()).isZero();
    }

    @Test
    void doesNotRunAnyListenerWithoutCancel() {
        CancellationToken token = new CancellationToken();
        AtomicInteger runs = new AtomicInteger();

        token.onCancel(runs::incrementAndGet);

        assertThat(runs.get()).isZero();
    }

    /**
     * 并发压力/冒烟测试:高并发下反复让 onCancel 与 cancel 抢跑同一批监听器,断言每个
     * 监听器恰好执行一次、且无死锁或崩溃。
     *
     * <p>注意:旧实现的双跑/零跑�narrow窗口极窄,随机线程调度几乎无法稳定命中——本测试
     * 在旧实现上同样会通过,因此它不是该竞态的回归护栏。修复的正确性依赖 run-once 守卫的
     * 结构化保证(见 {@code CancellationToken} 的 exactly-once 论证),而非本测试的复现。
     */
    @Test
    void everyListenerRunsExactlyOnceUnderConcurrentRegistrationAndCancel() throws Exception {
        int iterations = 500;
        int listenersPerIteration = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < iterations; i++) {
                CancellationToken token = new CancellationToken();
                AtomicInteger[] counters = new AtomicInteger[listenersPerIteration];
                for (int c = 0; c < listenersPerIteration; c++) {
                    counters[c] = new AtomicInteger();
                }

                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(listenersPerIteration + 1);

                for (int c = 0; c < listenersPerIteration; c++) {
                    AtomicInteger counter = counters[c];
                    pool.execute(() -> {
                        awaitQuietly(start);
                        token.onCancel(counter::incrementAndGet);
                        done.countDown();
                    });
                }
                pool.execute(() -> {
                    awaitQuietly(start);
                    token.cancel();
                    done.countDown();
                });

                start.countDown();
                assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

                for (AtomicInteger counter : counters) {
                    assertThat(counter.get())
                            .as("listener must run exactly once regardless of registration/cancel interleaving")
                            .isEqualTo(1);
                }
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting start", e);
        }
    }
}
