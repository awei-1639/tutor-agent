package com.tutor.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.SideEffect;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Stable tool facade coordinating admission, idempotency, execution, and audit. */
@Component
public class ToolExecutor {
    private final ToolRegistry registry;
    private final ToolCallAuditor auditor;
    private final ToolIdempotencyStore idempotency;
    private final ObjectMapper mapper;
    private final ToolExecutionPolicy policy;
    private final ToolInvocationRunner runner;

    @Autowired
    public ToolExecutor(ToolRegistry registry, ToolCallAuditor auditor, ObjectMapper mapper,
                        ToolIdempotencyStore idempotency) {
        this(registry, auditor, mapper, idempotency, new ToolExecutionPolicy(), new ToolInvocationRunner());
    }

    public ToolExecutor(ToolRegistry registry, ToolCallAuditor auditor, ObjectMapper mapper) {
        this(registry, auditor, mapper, new ToolIdempotencyStore() {
            public java.util.Optional<Object> completed(long userId, String tool, String key) {
                return java.util.Optional.empty();
            }
            public boolean claim(long userId, String tool, String key) { return true; }
            public void complete(long userId, String tool, String key, Object result) { }
            public void release(long userId, String tool, String key) { }
        });
    }

    ToolExecutor(ToolRegistry registry, ToolCallAuditor auditor, ObjectMapper mapper,
                 ToolIdempotencyStore idempotency, ToolExecutionPolicy policy,
                 ToolInvocationRunner runner) {
        this.registry = registry;
        this.auditor = auditor;
        this.mapper = mapper;
        this.idempotency = idempotency;
        this.policy = policy;
        this.runner = runner;
    }

    public Object execute(String toolName, Object input, ToolExecutionContext context) {
        ToolRegistration registration = registry.require(toolName);
        String digest = digest(input);
        long started = System.nanoTime();
        String status = "failed";
        boolean claimed = false;
        try {
            policy.validate(registration, input, context);
            if (registration.spec().level() != SideEffect.L0) {
                idempotency.reclaimExpired(Duration.ofMinutes(10));
                var cached = idempotency.completed(context.userId(), toolName, context.idempotencyKey());
                if (cached.isPresent()) {
                    status = "idempotent_replay";
                    return cached.get();
                }
                if (!idempotency.claim(context.userId(), toolName, context.idempotencyKey())) {
                    throw new ToolExecutionException("IDEMPOTENCY_IN_PROGRESS", "相同幂等键的工具调用正在执行");
                }
                claimed = true;
            }
            Object result = runner.run(registration, input, context);
            status = "success";
            if (claimed) idempotency.complete(context.userId(), toolName, context.idempotencyKey(), result);
            return result;
        } catch (TimeoutException e) {
            throw new ToolExecutionException("TIMEOUT", "工具执行超时", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolExecutionException("CANCELLED", "工具执行被取消", e);
        } catch (ExecutionException e) {
            throw new ToolExecutionException("FAILED", "工具执行失败", e.getCause());
        } finally {
            if (claimed && !"success".equals(status)) {
                idempotency.release(context.userId(), toolName, context.idempotencyKey());
            }
            audit(new ToolCallRecord(context.traceId(), context.agent(), toolName, digest, status,
                    registration.spec().level().name(), elapsedMs(started), context.idempotencyKey()));
        }
    }

    public Object executeJson(String toolName, com.fasterxml.jackson.databind.JsonNode arguments,
                              ToolExecutionContext context) {
        return execute(toolName, registry.convertInput(toolName, arguments, mapper), context);
    }

    private String digest(Object input) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(input);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算工具参数摘要", e);
        }
    }

    private void audit(ToolCallRecord call) {
        try {
            auditor.record(call);
        } catch (RuntimeException ignored) {
        }
    }

    private long elapsedMs(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    @PreDestroy
    void shutdown() {
        runner.shutdown();
    }
}
