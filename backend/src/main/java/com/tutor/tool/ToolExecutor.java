package com.tutor.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.SideEffect;
import jakarta.annotation.PreDestroy;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class ToolExecutor {
    private final ToolRegistry registry;
    private final ToolCallAuditor auditor;
    private final ToolIdempotencyStore idempotency;
    private final ObjectMapper mapper;
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public ToolExecutor(ToolRegistry registry, ToolCallAuditor auditor, ObjectMapper mapper, ToolIdempotencyStore idempotency) {
        this.registry = registry;
        this.auditor = auditor;
        this.mapper = mapper;
        this.idempotency = idempotency;
    }

    public ToolExecutor(ToolRegistry registry, ToolCallAuditor auditor, ObjectMapper mapper) {
        this(registry, auditor, mapper, new ToolIdempotencyStore() {
            public java.util.Optional<Object> completed(long userId, String tool, String key) { return java.util.Optional.empty(); }
            public boolean claim(long userId, String tool, String key) { return true; }
            public void complete(long userId, String tool, String key, Object result) { }
            public void release(long userId, String tool, String key) { }
        });
    }

    public Object execute(String toolName, Object input, ToolExecutionContext context) {
        ToolRegistration registration = registry.require(toolName);
        String digest = digest(input);
        long started = System.nanoTime();
        String status = "failed";
        boolean claimed = false;
        try {
            validateAccess(registration, input, context);
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
            Future<Object> task = executor.submit(() -> registration.handler().execute(input, context));
            Object result;
            try {
                result = task.get(registration.spec().timeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                task.cancel(true);
                throw timeout;
            }
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
            if (claimed && !"success".equals(status)) idempotency.release(context.userId(), toolName, context.idempotencyKey());
            audit(new ToolCallRecord(context.traceId(), context.agent(), toolName, digest, status,
                    registration.spec().level().name(), elapsedMs(started), context.idempotencyKey()));
        }
    }

    public Object executeJson(String toolName, com.fasterxml.jackson.databind.JsonNode arguments,
                              ToolExecutionContext context) {
        return execute(toolName, registry.convertInput(toolName, arguments, mapper), context);
    }

    private void validateAccess(ToolRegistration registration, Object input, ToolExecutionContext context) {
        if (!registration.allowedAgents().contains(context.agent())) {
            throw new ToolExecutionException("FORBIDDEN", "当前 agent 无权调用工具");
        }
        SideEffect level = registration.spec().level();
        if (level != SideEffect.L0 && (context.idempotencyKey() == null || context.idempotencyKey().isBlank())) {
            throw new ToolExecutionException("IDEMPOTENCY_REQUIRED", "有副作用的工具必须提供幂等键");
        }
        if (level == SideEffect.L2 && !context.confirmed()) {
            throw new ToolExecutionException("CONFIRMATION_REQUIRED", "外部动作需要用户确认");
        }
        if (input == null || !registration.spec().inputSchema().isInstance(input)) {
            throw new ToolExecutionException("INVALID_INPUT", "工具参数类型不符合契约");
        }
        Set<ConstraintViolation<Object>> violations = validator.validate(input);
        if (!violations.isEmpty()) throw new ToolExecutionException("INVALID_INPUT", "工具参数校验失败");
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
        try { auditor.record(call); } catch (RuntimeException ignored) { }
    }

    private long elapsedMs(long started) { return Duration.ofNanos(System.nanoTime() - started).toMillis(); }

    @PreDestroy
    void shutdown() { executor.shutdownNow(); }
}
