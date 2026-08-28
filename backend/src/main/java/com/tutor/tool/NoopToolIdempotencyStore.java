package com.tutor.tool;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Profile("test")
public class NoopToolIdempotencyStore implements ToolIdempotencyStore {
    public Optional<Object> completed(long userId, String tool, String key) { return Optional.empty(); }
    public boolean claim(long userId, String tool, String key) { return true; }
    public void complete(long userId, String tool, String key, Object result) { }
    public void release(long userId, String tool, String key) { }
}
