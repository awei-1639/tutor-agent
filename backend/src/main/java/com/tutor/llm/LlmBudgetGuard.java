package com.tutor.llm;

import com.tutor.config.LlmProperties;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对 LLM 调用做数据库原子预算预留。
 * 预留发生在外呼之前，因此并发请求不会同时越过日限额或单轮限额。
 */
@Component
public class LlmBudgetGuard {
    private static final Logger log = LoggerFactory.getLogger(LlmBudgetGuard.class);
    private final JdbcTemplate jdbc;
    private final LlmProperties props;

    public LlmBudgetGuard(JdbcTemplate jdbc, LlmProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Transactional
    public void reserve(String traceId, long estimatedTokens) {
        long amount = Math.max(1, estimatedTokens);
        reserveTurn(traceId, amount);
        try {
            reserveDay(amount);
        } catch (RuntimeException error) {
            // 单轮预留不得在每日预留失败后继续存在。这在第二条语句位于真实事务外失败时
            // 尤其重要，例如测试、副本或瞬时数据库连接丢失。
            try {
                jdbc.update("""
                        UPDATE llm_turn_budget
                        SET reserved_tokens=GREATEST(reserved_tokens-?,0)
                        WHERE trace_id=?
                        """, amount, traceId);
            } catch (RuntimeException rollbackFailure) {
                // 不得掩盖每日限额失败。真实事务会回滚两条语句；此日志覆盖非事务测试
                // 适配器和瞬时连接失败。
                log.error("failed to roll back turn token reservation trace={}: {}",
                        traceId, rollbackFailure.getMessage());
            }
            throw error;
        }
    }

    private void reserveTurn(String traceId, long amount) {
        try {
            jdbc.queryForObject("""
                    INSERT INTO llm_turn_budget (trace_id, reserved_tokens)
                    VALUES (?, ?)
                    ON CONFLICT (trace_id) DO UPDATE
                    SET reserved_tokens = llm_turn_budget.reserved_tokens + EXCLUDED.reserved_tokens
                    WHERE llm_turn_budget.reserved_tokens + llm_turn_budget.actual_tokens
                            + EXCLUDED.reserved_tokens <= ?
                    RETURNING reserved_tokens
                    """, Long.class, traceId, amount, props.budget().turnTokenLimit());
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException("本轮 token 预算已用尽");
        }
    }

    private void reserveDay(long amount) {
        try {
            jdbc.queryForObject("""
                    INSERT INTO llm_daily_budget (budget_day, reserved_tokens)
                    VALUES (CURRENT_DATE, ?)
                    ON CONFLICT (budget_day) DO UPDATE
                    SET reserved_tokens = llm_daily_budget.reserved_tokens + EXCLUDED.reserved_tokens
                    WHERE llm_daily_budget.reserved_tokens + llm_daily_budget.actual_tokens
                            + EXCLUDED.reserved_tokens <= ?
                    RETURNING reserved_tokens
                    """, Long.class, amount, props.budget().dailyTokenLimit());
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException("每日 token 限额已用尽");
        }
    }

    @Transactional
    public void settle(String traceId, long reservedTokens, long actualTokens) {
        long reserved = Math.max(0, reservedTokens);
        // 供应商上报的用量不得让 actual_tokens 超过本次调用原子预留的数量。估算值被
        // 刻意作为记账硬上限；分词方式不兼容的供应商不得绕过每日/单轮限额。
        long actual = Math.min(reserved, Math.max(0, actualTokens));
        jdbc.update("""
                UPDATE llm_turn_budget
                SET reserved_tokens=GREATEST(reserved_tokens-?,0), actual_tokens=actual_tokens+?
                WHERE trace_id=?
                """, reserved, actual, traceId);
        jdbc.update("""
                UPDATE llm_daily_budget
                SET reserved_tokens=GREATEST(reserved_tokens-?,0), actual_tokens=actual_tokens+?
                WHERE budget_day=CURRENT_DATE
                """, reserved, actual);
    }

    @Scheduled(cron = "0 10 3 * * *")
    public void purgeBudgetRows() {
        jdbc.update("DELETE FROM llm_turn_budget WHERE created_at < now() - INTERVAL '3 days'");
        jdbc.update("DELETE FROM llm_daily_budget WHERE budget_day < CURRENT_DATE - 30");
    }
}
