package com.tutor.llm;

import com.tutor.config.LlmProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

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
                .thenThrow(new EmptyResultDataAccessException(1));
        LlmBudgetGuard guard = new LlmBudgetGuard(jdbc, properties());

        assertThatThrownBy(() -> guard.reserve("trace", 50))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("每日 token 限额已用尽");

        verify(jdbc).update(contains("UPDATE llm_turn_budget"), eq(50L), eq("trace"));
    }

    private static LlmProperties properties() {
        return new LlmProperties(
                new LlmProperties.Endpoint("deepseek-key", "https://api.deepseek.com"),
                new LlmProperties.Endpoint("silicon-key", "https://api.siliconflow.cn/v1"),
                Map.of("chat", "chat", "router", "router", "expert", "expert", "summary", "summary",
                        "extract", "extract", "embed", "embed"),
                new LlmProperties.Budget(100_000, 10_000),
                new LlmProperties.Timeout(1, 60, 120, 25));
    }
}
