package com.tutor.conversation.chat.support;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TraceRecorderTest {

    @Test
    void defersTracePersistenceUntilTheExecutorRuns() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AtomicReference<Runnable> task = new AtomicReference<>();
        TraceRecorder recorder = new TraceRecorder(jdbc, task::set, new SimpleMeterRegistry());

        recorder.span("trace", 7L, "retrieve", System.currentTimeMillis(), false, Map.of("hops", 2));

        verify(jdbc, never()).update(anyString(), any(Object[].class));
        task.get().run();
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void dropsTraceWhenThePersistenceQueueIsFull() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TraceRecorder recorder = new TraceRecorder(jdbc, task -> {
            throw new RejectedExecutionException();
        }, registry);

        recorder.span("trace", 7L, "retrieve", System.currentTimeMillis(), false);

        verify(jdbc, never()).update(anyString(), any(Object[].class));
        org.assertj.core.api.Assertions.assertThat(registry.get("tutor.trace.persistence.dropped").counter().count())
                .isEqualTo(1D);
    }
}
