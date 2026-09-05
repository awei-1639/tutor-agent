package com.tutor.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.SideEffect;
import com.tutor.contract.ToolSpec;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolExecutorTest {
    private final ToolRegistry registry = new ToolRegistry();
    private final List<ToolCallRecord> calls = new ArrayList<>();
    private final ToolExecutor executor = new ToolExecutor(registry, calls::add, new ObjectMapper());

    @Test
    void validatesAgentInputAndRecordsSuccessfulCall() {
        registry.register(new ToolRegistration(
                new ToolSpec("echo", Input.class, Duration.ofSeconds(1), true, SideEffect.L0),
                java.util.Set.of("chat"),
                (input, context) -> ((Input) input).value()));

        Object result = executor.execute("echo", new Input("hello"),
                new ToolExecutionContext("trace-1", "chat", 7, null, false));

        assertThat(result).isEqualTo("hello");
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.status()).isEqualTo("success");
            assertThat(call.tool()).isEqualTo("echo");
            assertThat(call.argsDigest()).hasSize(64);
        });
    }

    @Test
    void rejectsUnauthorizedAgentAndAuditsFailure() {
        registry.register(new ToolRegistration(
                new ToolSpec("internal", Input.class, Duration.ofSeconds(1), true, SideEffect.L0),
                java.util.Set.of("planner"),
                (input, context) -> "never"));

        assertThatThrownBy(() -> executor.execute("internal", new Input("x"),
                new ToolExecutionContext("trace-2", "chat", 7, null, false)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessage("当前 agent 无权调用工具");
        assertThat(calls).singleElement().extracting(ToolCallRecord::status).isEqualTo("failed");
    }

    @Test
    void requiresIdempotencyAndConfirmationForSideEffects() {
        registry.register(new ToolRegistration(
                new ToolSpec("send", Input.class, Duration.ofSeconds(1), false, SideEffect.L2),
                java.util.Set.of("push"),
                (input, context) -> "sent"));
        ToolExecutionContext missingKey = new ToolExecutionContext("trace-3", "push", 7, null, false);

        assertThatThrownBy(() -> executor.execute("send", new Input("x"), missingKey))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessage("有副作用的工具必须提供幂等键");
        assertThatThrownBy(() -> executor.execute("send", new Input("x"),
                new ToolExecutionContext("trace-4", "push", 7, "key-1", false)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessage("外部动作需要用户确认");
    }

    @Test
    void cancelsTimedOutHandlerAndRecordsTimeout() {
        registry.register(new ToolRegistration(
                new ToolSpec("slow", Input.class, Duration.ofMillis(20), true, SideEffect.L0),
                java.util.Set.of("chat"),
                (input, context) -> { Thread.sleep(500); return "late"; }));

        assertThatThrownBy(() -> executor.execute("slow", new Input("x"),
                new ToolExecutionContext("trace-5", "chat", 7, null, false)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessage("工具执行超时");
        assertThat(calls).singleElement().extracting(ToolCallRecord::status).isEqualTo("failed");
    }

    @Test
    void replaysCompletedSideEffectWithoutRunningHandlerAgain() {
        AtomicInteger runs = new AtomicInteger();
        MemoryIdempotencyStore store = new MemoryIdempotencyStore();
        ToolExecutor sideEffectExecutor = new ToolExecutor(registry, calls::add, new ObjectMapper(), store);
        registry.register(new ToolRegistration(
                new ToolSpec("write", Input.class, Duration.ofSeconds(1), false, SideEffect.L1),
                java.util.Set.of("planner"),
                (input, context) -> { runs.incrementAndGet(); return "created"; }));
        ToolExecutionContext context = new ToolExecutionContext("trace-6", "planner", 7, "write-1", false);

        assertThat(sideEffectExecutor.execute("write", new Input("x"), context)).isEqualTo("created");
        assertThat(sideEffectExecutor.execute("write", new Input("x"), context)).isEqualTo("created");
        assertThat(runs).hasValue(1);
        assertThat(store.reclaimed).isTrue();
    }

    private static final class MemoryIdempotencyStore implements ToolIdempotencyStore {
        private Object result;
        private boolean claimed;
        private boolean reclaimed;
        public Optional<Object> completed(long userId, String tool, String key) { return result == null ? Optional.empty() : Optional.of(result); }
        public boolean claim(long userId, String tool, String key) { if (claimed) return false; claimed = true; return true; }
        public void complete(long userId, String tool, String key, Object value) { result = value; }
        public void release(long userId, String tool, String key) { claimed = false; }
        public void reclaimExpired(java.time.Duration age) { reclaimed = true; }
    }

    record Input(@NotBlank String value) {}
}
