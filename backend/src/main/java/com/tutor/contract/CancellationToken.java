package com.tutor.contract;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 由 SSE 边界和异步工作共享的请求级取消信号。请求取消时，监听器最多调用一次。
 */
public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public boolean isCancelled() {
        return cancelled.get();
    }

    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return false;
        }
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // 即使一个清理钩子失败，取消操作仍必须保持尽力而为。
            }
        }
        listeners.clear();
        return true;
    }

    /**
     * 注册清理钩子，并返回一个可在正常完成后移除该钩子的句柄。
     */
    public AutoCloseable onCancel(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        if (cancelled.get()) {
            listener.run();
            return () -> { };
        }
        listeners.add(listener);
        if (cancelled.get() && listeners.remove(listener)) {
            listener.run();
        }
        return () -> listeners.remove(listener);
    }
}
