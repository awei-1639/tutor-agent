package com.tutor.memory.external;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MemorySyncMetricsTest {
    @Test
    void refreshesBacklogAndTerminalFailureGauges() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(4, 2);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemorySyncMetrics metrics = new MemorySyncMetrics(jdbc, registry);

        metrics.refresh();

        assertThat(registry.get("tutor.memory.sync.backlog").gauge().value()).isEqualTo(4D);
        assertThat(registry.get("tutor.memory.sync.failed").gauge().value()).isEqualTo(2D);
    }

    @Test
    void ignoresDatabaseFailureDuringRefresh() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemorySyncMetrics metrics = new MemorySyncMetrics(jdbc, registry);

        metrics.refresh();

        assertThat(metrics.backlog()).isZero();
        assertThat(metrics.failed()).isZero();
    }
}
