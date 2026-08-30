package com.tutor.llm;

import com.tutor.config.LlmProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmBudgetGuardTest {
    @Mock
    JdbcTemplate jdbc;

    @Test
    void rollsBackTurnReservationWhenDailyReservationFails() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(1L)
                .thenReturn(null)
                .thenThrow(new EmptyResultDataAccessException(1));
        LlmBudgetGuard guard = new LlmBudgetGuard(jdbc, properties());

        assertThatThrownBy(() -> guard.reserve("trace", 50, false))
                .isInstanceOf(BudgetExhausted.class)
                .hasMessage("每日 token 限额已用尽");

        verify(jdbc).update(contains("UPDATE llm_turn_budget"), eq(50L), eq("trace"));
    }

    @Test
    void throwsTypedUserDailyExceptionAndRollsBackWhenUserQuotaExhausted() {
        // reserveTurn 成功 → 归属用户 7 → 用户层配额条件不满足 (RETURNING 无行)。
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(1L)
                .thenReturn(7L)
                .thenThrow(new EmptyResultDataAccessException(1));
        LlmBudgetGuard guard = new LlmBudgetGuard(jdbc, properties());

        assertThatThrownBy(() -> guard.reserve("trace", 50, false))
                .isInstanceOf(BudgetExhausted.class)
                .satisfies(error -> assertThat(((BudgetExhausted) error).kind())
                        .isEqualTo(BudgetExhausted.Kind.USER_DAILY));

        verify(jdbc).update(contains("UPDATE llm_turn_budget"), eq(50L), eq("trace"));
    }

    @Test
    void defersBackgroundTaskWhenBackgroundShareExhausted() {
        // reserveTurn 成功 → 无归属用户 (评测/入库 trace) → 后台子预算条件不满足。
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(1L)
                .thenReturn(null)
                .thenThrow(new EmptyResultDataAccessException(1));
        LlmBudgetGuard guard = new LlmBudgetGuard(jdbc, properties());

        assertThatThrownBy(() -> guard.reserve("ingest-trace", 50, true))
                .isInstanceOf(BudgetExhausted.class)
                .satisfies(error -> assertThat(((BudgetExhausted) error).kind())
                        .isEqualTo(BudgetExhausted.Kind.BACKGROUND_DEFERRED));

        verify(jdbc).update(contains("UPDATE llm_turn_budget"), eq(50L), eq("ingest-trace"));
    }

    @Test
    void defersBackgroundTaskImmediatelyWhenPressureIsSevere() {
        LlmBudgetGuard guard = new LlmBudgetGuard(jdbc, properties());
        BudgetPressureService pressure = org.mockito.Mockito.mock(BudgetPressureService.class);
        org.mockito.Mockito.when(pressure.backgroundAllowed()).thenReturn(false);
        guard.setBudgetPressure(pressure);

        assertThatThrownBy(() -> guard.reserve("trace", 50, true))
                .isInstanceOf(BudgetExhausted.class)
                .satisfies(error -> assertThat(((BudgetExhausted) error).kind())
                        .isEqualTo(BudgetExhausted.Kind.BACKGROUND_DEFERRED));

        verify(jdbc, org.mockito.Mockito.never()).queryForObject(anyString(), eq(Long.class), any(Object[].class));
    }

    @Test
    void settleWritesActualUsageBackToTurnDailyAndUserLayers() {
        LlmBudgetGuard guard = new LlmBudgetGuard(jdbc, properties());
        guard.settle(new LlmBudgetGuard.Reservation("trace", 500, true, 7L), 320);

        verify(jdbc).update(contains("UPDATE llm_turn_budget"), eq(500L), eq(320L), eq("trace"));
        verify(jdbc).update(contains("SET reserved_tokens=GREATEST(reserved_tokens-?,0), actual_tokens=actual_tokens+?"),
                eq(500L), eq(320L));
        verify(jdbc).update(contains("SET background_reserved_tokens=GREATEST"), eq(500L), eq(320L));
        verify(jdbc).update(contains("UPDATE llm_user_budget"), eq(500L), eq(320L), eq(7L));
    }

    @Test
    void countsBudgetRejectionsByKind() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        LlmBudgetGuard guard = new LlmBudgetGuard(jdbc, properties());
        guard.setLlmBudgetMetrics(new LlmBudgetMetrics(registry));
        // 单轮预留条件即不满足 → TURN，前台与后台各拒绝一次。
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThatThrownBy(() -> guard.reserve("trace", 50, false))
                .isInstanceOf(BudgetExhausted.class);
        assertThatThrownBy(() -> guard.reserve("trace", 50, true))
                .isInstanceOf(BudgetExhausted.class);

        assertThat(registry.counter("tutor.llm.budget.rejected", "kind", "turn").count()).isEqualTo(2.0);
    }

    @Test
    void countsBackgroundDeferralAndUserDailyRejections() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        LlmBudgetGuard guard = new LlmBudgetGuard(jdbc, properties());
        guard.setLlmBudgetMetrics(new LlmBudgetMetrics(registry));
        BudgetPressureService pressure = org.mockito.Mockito.mock(BudgetPressureService.class);
        org.mockito.Mockito.when(pressure.backgroundAllowed()).thenReturn(false);
        guard.setBudgetPressure(pressure);

        assertThatThrownBy(() -> guard.reserve("trace", 50, true))
                .isInstanceOf(BudgetExhausted.class);
        assertThat(registry.counter("tutor.llm.budget.rejected", "kind", "background_deferred").count())
                .isEqualTo(1.0);

        // 归属用户后用户层配额条件不满足 → USER_DAILY 计数。
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(1L)
                .thenReturn(7L)
                .thenThrow(new EmptyResultDataAccessException(1));
        assertThatThrownBy(() -> guard.reserve("trace", 50, false))
                .isInstanceOf(BudgetExhausted.class);
        assertThat(registry.counter("tutor.llm.budget.rejected", "kind", "user_daily").count())
                .isEqualTo(1.0);
    }

    @Test
    void requireUserDailyAllowanceReturnsPercentOrThrowsCounted() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        LlmBudgetGuard guard = new LlmBudgetGuard(jdbc, properties());
        guard.setLlmBudgetMetrics(new LlmBudgetMetrics(registry));
        // 剩余 90,000 / 默认 300,000 = 30% → 放行并返回百分比。
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(90_000L);
        assertThat(guard.requireUserDailyAllowance(7L)).isEqualTo(30);

        // 剩余 0 → USER_DAILY，计入拒绝指标。
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(0L);
        assertThatThrownBy(() -> guard.requireUserDailyAllowance(7L))
                .isInstanceOf(BudgetExhausted.class)
                .satisfies(error -> assertThat(((BudgetExhausted) error).kind())
                        .isEqualTo(BudgetExhausted.Kind.USER_DAILY));
        assertThat(registry.counter("tutor.llm.budget.rejected", "kind", "user_daily").count())
                .isEqualTo(1.0);
    }

    private static LlmProperties properties() {
        return new LlmProperties(
                new LlmProperties.Endpoint("deepseek-key", "https://api.deepseek.com"),
                new LlmProperties.Endpoint("silicon-key", "https://api.siliconflow.cn/v1"),
                Map.of("chat", "chat", "router", "router", "expert", "expert", "summary", "summary",
                        "extract", "extract", "embed", "embed"),
                new LlmProperties.Budget(100_000, 10_000),
                new LlmProperties.Timeout(1, 60, 120, 25), LlmProperties.TokenLimits.defaults());
    }
}
