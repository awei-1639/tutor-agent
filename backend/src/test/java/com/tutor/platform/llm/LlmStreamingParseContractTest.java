package com.tutor.platform.llm;

import com.tutor.contract.CancellationToken;
import com.tutor.platform.config.LlmProperties;
import com.tutor.platform.text.TokenBudget;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 供应商流式解析契约: 任何 OpenAI 兼容供应商的原始事件 (content/reasoning_content/
 * usage/finish_reason) 经过同一解析器必须产出同一套归一化回调。
 * 换供应商时本测试就是稳定性与质量分离的第一道闸门。
 */
class LlmStreamingParseContractTest {

    private LlmStreamingExecutor executor() {
        var endpoint = new LlmProperties.Endpoint("key", "https://provider.example/v1");
        var properties = new LlmProperties(endpoint, endpoint, null,
                new LlmProperties.Budget(100_000, 10_000),
                new LlmProperties.Timeout(20, 60, 120, 60),
                LlmProperties.TokenLimits.defaults());
        return new LlmStreamingExecutor(new ChatStreamProviderClient(properties),
                new TokenBudget(), new LlmRequestPolicy(properties, new TokenBudget()));
    }

    private record Events(StringBuilder tokens, StringBuilder reasoning,
                          AtomicLong input, AtomicLong output, AtomicBoolean truncated) {}

    private Events consume(String[] events, int maxOutputTokens) throws IOException {
        var ex = executor();
        var tokens = new StringBuilder();
        var reasoning = new StringBuilder();
        var truncated = new AtomicBoolean();
        var input = new AtomicLong();
        var output = new AtomicLong();
        var actual = new AtomicLong();
        var handler = new LlmStreamHandler() {
            @Override public void onToken(String t) { tokens.append(t); }
            @Override public void onReasoning(String t) { reasoning.append(t); }
            @Override public void onComplete(LlmStreamResult r) { }
            @Override public void onError(Throwable e) { }
        };
        var full = new StringBuilder();
        for (String event : events) {
            assertFalse(ex.consumeSseData(event, full, input, output, actual, truncated, handler,
                    new CancellationToken(), maxOutputTokens));
        }
        return new Events(tokens, reasoning, input, output, truncated);
    }

    @Test
    void normalizesContentReasoningUsageAndTruncation() throws IOException {
        var events = consume(new String[] {
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"让我想想\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"A2A\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"是协议\"}}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}"
        }, 100);
        assertEquals("A2A是协议", events.tokens().toString());
        assertEquals("让我想想", events.reasoning().toString());
        assertTrue(events.truncated().get());
        assertEquals(10, events.input().get());
        assertEquals(5, events.output().get());
    }

    @Test
    void reasoningDeltasDoNotConsumeTheAnswerTokenBudget() throws IOException {
        var thinking = "很长的推理过程".repeat(20);
        var events = consume(new String[] {
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"" + thinking + "\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"答案\"}}]}"
        }, 20);
        assertEquals("答案", events.tokens().toString());
    }

    @Test
    void doneMarkerSignalsTerminalAndMalformedLinesAreTolerated() throws IOException {
        var ex = executor();
        var full = new StringBuilder();
        var truncated = new AtomicBoolean();
        var input = new AtomicLong();
        var output = new AtomicLong();
        var actual = new AtomicLong();
        var handler = new LlmStreamHandler() {
            @Override public void onToken(String t) { }
            @Override public void onComplete(LlmStreamResult r) { }
            @Override public void onError(Throwable e) { }
        };
        assertFalse(ex.consumeSseData("not-json-at-all", full, input, output, actual, truncated, handler,
                new CancellationToken(), 100));
        assertTrue(ex.consumeSseData("[DONE]", full, input, output, actual, truncated, handler,
                new CancellationToken(), 100));
    }
}
