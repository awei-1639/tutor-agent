package com.tutor.platform.llm;

import com.tutor.platform.config.LlmProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BudgetPressureServiceTest {
    @Mock
    JdbcTemplate jdbc;
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    @Test
    void assumesNormalWhenSnapshotUnavailable() {
        when(jdbc.queryForObject(anyString(), eq(Long.class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        BudgetPressureService service = new BudgetPressureService(jdbc, properties(), registry);

        assertThat(service.level()).isEqualTo(BudgetPressureService.Level.NORMAL);
        assertThat(service.multiHopAllowed()).isTrue();
        assertThat(service.backgroundAllowed()).isTrue();
    }

    @Test
    void elevatesAtEightyPercentAndShedsQualityFeatures() {
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1_600_000L);

        BudgetPressureService service = new BudgetPressureService(jdbc, properties(), registry);

        assertThat(service.level()).isEqualTo(BudgetPressureService.Level.ELEVATED);
        assertThat(service.multiHopAllowed()).isFalse();
        assertThat(service.maxExperts()).isEqualTo(1);
        assertThat(service.chatOutputCap(1_600)).isEqualTo(1_000);
        // ELEVATED 只是收紧前台质量特性，后台仍允许运行。
        assertThat(service.backgroundAllowed()).isTrue();
        assertThat(registry.get("tutor.llm.budget.daily.used.percent").gauge().value()).isEqualTo(80.0);
    }

    @Test
    void defersBackgroundAtSeverePressure() {
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1_950_000L);

        BudgetPressureService service = new BudgetPressureService(jdbc, properties(), registry);

        assertThat(service.level()).isEqualTo(BudgetPressureService.Level.SEVERE);
        assertThat(service.backgroundAllowed()).isFalse();
        assertThat(service.severePressure()).isTrue();
    }

    @Test
    void exhaustsAtFullDailyBudget() {
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(2_500_000L);

        BudgetPressureService service = new BudgetPressureService(jdbc, properties(), registry);

        assertThat(service.level()).isEqualTo(BudgetPressureService.Level.EXHAUSTED);
        assertThat(service.backgroundAllowed()).isFalse();
    }

    private static LlmProperties properties() {
        return new LlmProperties(
                new LlmProperties.Endpoint("deepseek-key", "https://api.deepseek.com"),
                new LlmProperties.Endpoint("silicon-key", "https://api.siliconflow.cn/v1"),
                Map.of("chat", "chat", "embed", "embed"),
                new LlmProperties.Budget(2_000_000, 50_000),
                new LlmProperties.Timeout(1, 60, 120, 25), LlmProperties.TokenLimits.defaults());
    }
}
