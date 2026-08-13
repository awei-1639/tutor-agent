package com.tutor.contract;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Request-scoped cancellation signal shared by the SSE boundary and async work.
 * Listeners are invoked at most once when the request is cancelled.
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
                // Cancellation must remain best-effort even if one cleanup hook fails.
            }
        }
        listeners.clear();
        return true;
    }

    /**
     * Registers a cleanup hook and returns a handle that removes it after normal completion.
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
