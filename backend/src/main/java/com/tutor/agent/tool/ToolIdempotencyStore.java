package com.tutor.agent.tool;

import java.util.Optional;
import java.time.Duration;

public interface ToolIdempotencyStore {
    Optional<Object> completed(long userId, String tool, String key);
    boolean claim(long userId, String tool, String key);
    void complete(long userId, String tool, String key, Object result);
    void release(long userId, String tool, String key);

    default void reclaimExpired(Duration age) { }
}
