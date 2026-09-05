package com.tutor.knowledge.retrieval.resilience;

import com.tutor.platform.config.Neo4jProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Neo4j 查询级超时之外的进程内熔断器。
 * 失败时返回 unavailable，由调用方选择安全空结果；readyz 仍会严格失败。
 */
@Component
public class Neo4jResilience {
    private static final Logger log = LoggerFactory.getLogger(Neo4jResilience.class);
    private static final long HALF_OPEN = -1L;

    private final int failureThreshold;
    private final long openNanos;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicLong openedAtNanos = new AtomicLong();

    @Autowired
    public Neo4jResilience(Neo4jProperties properties) {
        this(properties.failureThreshold(), Duration.ofSeconds(properties.openSeconds()));
    }

    public Neo4jResilience(int failureThreshold, Duration openDuration) {
        if (failureThreshold <= 0) throw new IllegalArgumentException("failure threshold must be positive");
        if (openDuration.isNegative() || openDuration.isZero()) {
            throw new IllegalArgumentException("open duration must be positive");
        }
        this.failureThreshold = failureThreshold;
        this.openNanos = openDuration.toNanos();
    }

    public <T> QueryResult<T> execute(String operation, Supplier<T> action) {
        if (!allowRequest()) {
            log.debug("Neo4j circuit open, fallback operation={}", operation);
            return QueryResult.unavailable();
        }
        try {
            T value = action.get();
            recordSuccess();
            return QueryResult.available(value);
        } catch (RuntimeException e) {
            recordFailure(operation, e);
            return QueryResult.unavailable();
        }
    }

    private boolean allowRequest() {
        long openedAt = openedAtNanos.get();
        if (openedAt == 0L) return true;
        if (openedAt == HALF_OPEN) return false;
        if (System.nanoTime() - openedAt < openNanos) return false;
        return openedAtNanos.compareAndSet(openedAt, HALF_OPEN);
    }

    private void recordSuccess() {
        failures.set(0);
        openedAtNanos.set(0L);
    }

    private void recordFailure(String operation, RuntimeException error) {
        int count = failures.incrementAndGet();
        if (count >= failureThreshold || openedAtNanos.get() == HALF_OPEN) {
            openedAtNanos.set(System.nanoTime());
            log.warn("Neo4j circuit opened operation={} failures={}: {}", operation, count, error.getMessage());
        } else {
            log.warn("Neo4j query failed operation={} failures={}/{}: {}",
                    operation, count, failureThreshold, error.getMessage());
        }
    }

    public record QueryResult<T>(boolean available, T value) {
        public static <T> QueryResult<T> available(T value) {
            return new QueryResult<>(true, value);
        }

        public static <T> QueryResult<T> unavailable() {
            return new QueryResult<>(false, null);
        }
    }
}
