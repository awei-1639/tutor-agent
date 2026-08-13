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
            // The turn reservation must not survive a failed daily reservation.
            // This is especially important when the second statement fails outside
            // a real transaction (tests, replicas, or a transient DB connection loss).
            try {
                jdbc.update("""
                        UPDATE llm_turn_budget
                        SET reserved_tokens=GREATEST(reserved_tokens-?,0)
                        WHERE trace_id=?
                        """, amount, traceId);
            } catch (RuntimeException rollbackFailure) {
                // Never hide the daily-limit failure. A real transaction will
                // roll back both statements; this log covers non-transactional
                // test adapters and transient connection failures.
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
        // Never let provider-reported usage push actual_tokens beyond the amount
        // atomically reserved for this call. The estimate is intentionally the
        // hard accounting ceiling; a provider with incompatible tokenization must
        // not bypass the daily/turn limit.
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
