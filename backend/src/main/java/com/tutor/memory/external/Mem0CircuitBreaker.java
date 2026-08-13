package com.tutor.memory.external;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Mem0 的进程级快速熔断；应用重启后状态自然清空。 */
@Component
public class Mem0CircuitBreaker {
    private final int failureThreshold;
    private final long openMillis;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicLong openedAt = new AtomicLong(0);

    public Mem0CircuitBreaker(
            @Value("${memory.mem0.failure-threshold:3}") int failureThreshold,
            @Value("${memory.mem0.open-seconds:30}") int openSeconds) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openMillis = Math.max(1, openSeconds) * 1000L;
    }

    public boolean allowRequest() {
        long opened = openedAt.get();
        return opened == 0 || System.currentTimeMillis() - opened >= openMillis;
    }

    public void success() {
        failures.set(0);
        openedAt.set(0);
    }

    public void failure() {
        if (failures.incrementAndGet() >= failureThreshold) openedAt.compareAndSet(0, System.currentTimeMillis());
    }
}
