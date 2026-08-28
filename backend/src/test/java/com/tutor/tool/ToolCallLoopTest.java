package com.tutor.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.Purpose;
import com.tutor.contract.SideEffect;
import com.tutor.contract.ToolSpec;
import com.tutor.llm.JsonGenerationGateway;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolCallLoopTest {
    @Test
    void executesToolThenReturnsFinalAnswer() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ToolRegistration(new ToolSpec("echo", Input.class, Duration.ofSeconds(1), true, SideEffect.L0),
                Set.of("chat"), (input, context) -> ((Input) input).value()));
        JsonGenerationGateway gateway = new SequenceGateway(
                "{\"type\":\"tool_call\",\"tool\":\"echo\",\"arguments\":{\"value\":\"hello\"}}",
                "{\"type\":\"final\",\"answer\":\"工具返回：hello\"}");
        ToolCallLoop loop = new ToolCallLoop(gateway, new ToolExecutor(registry, call -> { }, new ObjectMapper()), new ObjectMapper());

        ToolCallLoop.LoopResult result = loop.run(Purpose.CHAT, List.of(UserMessage.from("查询")), "trace-1",
                new ToolExecutionContext("trace-1", "chat", 7, null, false));

        assertThat(result.answer()).isEqualTo("工具返回：hello");
        assertThat(result.steps()).isEqualTo(1);
        assertThat(result.usedTools()).containsExactly("echo");
    }

    @Test
    void rejectsRepeatedIdenticalToolCall() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ToolRegistration(new ToolSpec("echo", Input.class, Duration.ofSeconds(1), true, SideEffect.L0),
                Set.of("chat"), (input, context) -> "ok"));
        JsonGenerationGateway gateway = new SequenceGateway(
                "{\"type\":\"tool_call\",\"tool\":\"echo\",\"arguments\":{\"value\":\"same\"}}",
                "{\"type\":\"tool_call\",\"tool\":\"echo\",\"arguments\":{\"value\":\"same\"}}");
        ToolCallLoop loop = new ToolCallLoop(gateway, new ToolExecutor(registry, call -> { }, new ObjectMapper()), new ObjectMapper());

        assertThatThrownBy(() -> loop.run(Purpose.CHAT, List.of(), "trace-2",
                new ToolExecutionContext("trace-2", "chat", 7, null, false)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessage("模型重复请求相同工具");
    }

    @Test
    void repairsInvalidStructuredOutputBeforeExecutingAnyTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ToolRegistration(new ToolSpec("echo", Input.class,
                        Duration.ofSeconds(1), true, SideEffect.L0),
                Set.of("chat"), (input, context) -> "should-not-run"));
        JsonGenerationGateway gateway = new SequenceGateway(
                "{\"type\":\"tool_call\",\"tool\":\"echo\"}",
                "{\"type\":\"final\",\"answer\":\"已修复\"}");
        ToolCallLoop loop = new ToolCallLoop(
                gateway,
                new ToolExecutor(registry, call -> { throw new AssertionError("tool must not run"); }, new ObjectMapper()),
                new ObjectMapper());

        ToolCallLoop.LoopResult result = loop.run(
                Purpose.CHAT,
                List.of(UserMessage.from("查询")),
                "trace-repair",
                new ToolExecutionContext("trace-repair", "chat", 7, null, false));

        assertThat(result.answer()).isEqualTo("已修复");
        assertThat(result.usedTools()).isEmpty();
    }

    record Input(String value) { }

    private static final class SequenceGateway implements JsonGenerationGateway {
        private final String[] responses;
        private int index;
        private SequenceGateway(String... responses) { this.responses = responses; }
        public String chatJson(Purpose purpose, List<ChatMessage> messages, String traceId) { return responses[Math.min(index++, responses.length - 1)]; }
        public String chatJson(Purpose purpose, List<ChatMessage> messages, String traceId, Duration timeout, int maxAttempts) { return chatJson(purpose, messages, traceId); }
    }
}
