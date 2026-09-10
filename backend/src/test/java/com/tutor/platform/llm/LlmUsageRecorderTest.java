package com.tutor.platform.llm;

import com.tutor.contract.Purpose;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LlmUsageRecorderTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LlmUsageRecorder recorder = new LlmUsageRecorder(jdbc, registry);

    @Test
    void recordsUsageRowAndMicrometerCounters() {
        recorder.record("trace-1", Purpose.ROUTER, "deepseek-chat", 1200, 160, 350, "ok");

        verify(jdbc).update(anyString(), anyString(), anyString(), anyString(),
                any(), any(), anyInt(), anyString());
        assertThat(registry.counter("tutor.llm.calls", "purpose", "router",
                "model", "deepseek-chat", "status", "ok").count()).isEqualTo(1.0);
        assertThat(registry.counter("tutor.llm.tokens", "purpose", "router",
                "model", "deepseek-chat", "direction", "input").count()).isEqualTo(1200.0);
        assertThat(registry.counter("tutor.llm.tokens", "purpose", "router",
                "model", "deepseek-chat", "direction", "output").count()).isEqualTo(160.0);
    }

    @Test
    void stillReportsCountersWhenDatabaseWriteFails() {
        doThrow(new RuntimeException("db down")).when(jdbc)
                .update(anyString(), anyString(), anyString(), anyString(),
                        any(), any(), anyInt(), anyString());

        recorder.record("trace-2", Purpose.CHAT, "deepseek-chat", 800, 0, 100, "error");

        assertThat(registry.counter("tutor.llm.calls", "purpose", "chat",
                "model", "deepseek-chat", "status", "error").count()).isEqualTo(1.0);
        assertThat(registry.counter("tutor.llm.tokens", "purpose", "chat",
                "model", "deepseek-chat", "direction", "input").count()).isEqualTo(800.0);
    }

    @Test
    void skipsZeroSizedTokenCounters() {
        recorder.record("trace-3", Purpose.EMBED, "BAAI/bge-m3", 64, 0, 90, "ok");

        assertThat(registry.get("tutor.llm.tokens").meters()).allSatisfy(m ->
                assertThat(m.getId().getTag("direction")).isEqualTo("input"));
    }
}
