package com.tutor.contract;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 由 SSE 边界和异步工作共享的请求级取消信号。请求取消时，每个监听器恰好执行一次。
 *
 * <p>并发保证:{@link #cancel()} 与 {@link #onCancel(Runnable)} 可在不同线程并发调用。
 * 每个注册的监听器都被包进一个 run-once 守卫({@link Guarded}),因此无论它被 {@code cancel()}
 * 的遍历、注册时的补触发、还是两者同时命中,委托 {@code Runnable} 都只会真正执行一次;
 * 而只要取消已发生,该监听器就至少被触发一次——从列表移除只是尽力而为的清理,不再作为
 * 执行的前提。这样就同时堵住了旧实现里"加入与清空交错"导致的双跑 / 零跑两个窄窗口。
 */
public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CopyOnWriteArrayList<Guarded> listeners = new CopyOnWriteArrayList<>();

    /** run-once 守卫:同一委托无论被哪条路径命中,只执行一次;单个钩子失败不影响其余。 */
    private static final class Guarded {
        private final Runnable delegate;
        private final AtomicBoolean ran = new AtomicBoolean();

        Guarded(Runnable delegate) {
            this.delegate = delegate;
        }

        void runOnce() {
            if (ran.compareAndSet(false, true)) {
                try {
                    delegate.run();
                } catch (RuntimeException ignored) {
                    // 即使一个清理钩子失败，取消操作仍必须保持尽力而为。
                }
            }
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return false;
        }
        for (Guarded listener : listeners) {
            listener.runOnce();
        }
        listeners.clear();
        return true;
    }

    /**
     * 注册清理钩子，并返回一个可在正常完成后移除该钩子的句柄。
     */
    public AutoCloseable onCancel(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        Guarded guarded = new Guarded(listener);
        if (cancelled.get()) {
            guarded.runOnce();
            return () -> { };
        }
        listeners.add(guarded);
        if (cancelled.get()) {
            // 注册与取消交错:cancel() 的快照可能未包含本监听器。此处无条件补触发,
            // run-once 守卫保证不会与 cancel() 的遍历重复执行;移除失败也不影响触发。
            guarded.runOnce();
            listeners.remove(guarded);
        }
        return () -> listeners.remove(guarded);
    }
}
