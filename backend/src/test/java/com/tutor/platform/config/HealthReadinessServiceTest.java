package com.tutor.platform.config;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 就绪探针必须快速失败: PG 宕机时 jdbc 检查阻塞在池等待 (~30s), 探针不能跟着挂 (演练 2026-10-25 S3)。 */
class HealthReadinessServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Driver neo4j = mock(Driver.class);
    private final HealthReadinessService service = new HealthReadinessService(jdbc, neo4j);

    @Test
    void healthyDependenciesReportNothingUnavailable() {
        when(jdbc.queryForObject(anyString(), any(Class.class))).thenReturn(1);

        assertThat(service.unavailableDependencies()).isEmpty();
    }

    @Test
    void hangingDatabaseProbeReportsUnavailableWithinTimeout() throws Exception {
        CountDownLatch hang = new CountDownLatch(1);
        when(jdbc.queryForObject(anyString(), any(Class.class)))
                .thenAnswer(inv -> {
                    hang.await(); // 模拟池等待阻塞
                    return 1;
                });

        long t0 = System.currentTimeMillis();
        List<String> unavailable = service.unavailableDependencies();
        long elapsed = System.currentTimeMillis() - t0;
        hang.countDown();

        assertThat(unavailable).contains("postgres");
        assertThat(elapsed).as("探针应在 %dms 内返回而非挂起", 3000).isLessThan(5000);
    }

    @Test
    void databaseErrorReportsUnavailable() {
        when(jdbc.queryForObject(anyString(), any(Class.class)))
                .thenThrow(new IllegalStateException("connection refused"));

        assertThat(service.unavailableDependencies()).contains("postgres");
    }
}
