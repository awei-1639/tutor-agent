package com.tutor.llm;

import com.tutor.config.LlmProperties;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对 LLM 调用做数据库原子预算预留。
 * 预留发生在外呼之前，因此并发请求不会同时越过单轮、用户、后台子预算或每日限额。
 *
 * 归属模型：回合编排方 (ChatService/InterviewTurnService/PlanService) 在回合开始时
 * 调用 attributeTrace 把 trace 归到用户，此后该 trace 内所有网关调用 (含后台的
 * 摘要/抽取) 都计入该用户的日配额；未归属的 trace (评测、知识入库) 只受全局与
 * 后台子预算约束。
 */
@Component
public class LlmBudgetGuard {
    private static final Logger log = LoggerFactory.getLogger(LlmBudgetGuard.class);
    private final JdbcTemplate jdbc;
    private final LlmProperties props;
    private volatile BudgetPressureService pressure;
    private volatile LlmBudgetMetrics metrics;

    public LlmBudgetGuard(JdbcTemplate jdbc, LlmProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** 预算压力服务可选注入，避免测试与无压力感知场景的构造耦合。 */
    @Autowired(required = false)
    void setBudgetPressure(BudgetPressureService pressure) {
        this.pressure = pressure;
    }

    /** 拒绝指标可选注入；未注入时只抛异常不计数。 */
    @Autowired(required = false)
    void setLlmBudgetMetrics(LlmBudgetMetrics metrics) {
        this.metrics = metrics;
    }

    /** 统一抛出口：所有层的预算拒绝都计入 tutor.llm.budget.rejected{kind}。 */
    private BudgetExhausted reject(BudgetExhausted.Kind kind, String message) {
        if (metrics != null) {
            metrics.countRejected(kind);
        }
        return new BudgetExhausted(kind, message);
    }

    /** 一次预留的完整信息；settle 需要它来回滚预留并把实际用量记账到对应层。 */
    public record Reservation(String traceId, long reservedTokens, boolean background, Long userId) {}

    /** 回合开始时把 trace 归属到用户。幂等；失败由调用方决定是否阻塞回合。 */
    @Transactional
    public void attributeTrace(String traceId, long userId) {
        jdbc.update("""
                INSERT INTO llm_turn_budget (trace_id, user_id)
                VALUES (?, ?)
                ON CONFLICT (trace_id) DO NOTHING
                """, traceId, userId);
    }

    /** 回合入口的快速失败：剩余配额 ≤0 抛 USER_DAILY（计入拒绝指标），否则返回剩余百分比。 */
    public int requireUserDailyAllowance(long userId) {
        int percent = userDailyRemainingPercent(userId);
        if (percent <= 0) {
            throw reject(BudgetExhausted.Kind.USER_DAILY, "用户每日 token 限额已用尽");
        }
        return percent;
    }

    /** 用户当日剩余配额百分比 (0-100)；无行视为全额。供回合开始的快速失败与前端额度展示。 */
    public int userDailyRemainingPercent(long userId) {
        long limit = Math.max(1, props.budget().userDailyTokenLimit());
        Long remaining = jdbc.queryForObject("""
                SELECT ? - COALESCE((SELECT reserved_tokens + actual_tokens FROM llm_user_budget
                                     WHERE user_id = ? AND budget_day = CURRENT_DATE), 0)
                """, Long.class, limit, userId);
        long safeRemaining = remaining == null ? limit : remaining;
        return (int) Math.max(0, Math.min(100, safeRemaining * 100 / limit));
    }

    @Transactional
    public Reservation reserve(String traceId, long estimatedTokens, boolean background) {
        long amount = Math.max(1, estimatedTokens);
        // SEVERE 压力下后台任务直接顺延，不做任何预留，也不产生预留churn。
        if (background && pressure != null && !pressure.backgroundAllowed()) {
            throw reject(BudgetExhausted.Kind.BACKGROUND_DEFERRED, "预算压力过高，后台任务顺延");
        }
        reserveTurn(traceId, amount);
        Long userId = attributedUser(traceId);
        boolean userReserved = false;
        boolean backgroundReserved = false;
        boolean dayReserved = false;
        try {
            if (userId != null) {
                reserveUser(userId, amount);
                userReserved = true;
            }
            if (background) {
                reserveBackgroundShare(amount);
                backgroundReserved = true;
            }
            reserveDay(amount);
            dayReserved = true;
        } catch (RuntimeException error) {
            // 单层预留不得在其他层预留失败后继续存在。真实事务会回滚全部语句；
            // 此补偿覆盖非事务测试适配器和瞬时连接失败。
            if (dayReserved) releaseDay(amount);
            if (backgroundReserved) releaseBackgroundShare(amount);
            if (userReserved) releaseUser(userId, amount);
            releaseTurn(traceId, amount);
            throw error;
        }
        return new Reservation(traceId, amount, background, userId);
    }

    @Transactional
    public void settle(Reservation reservation, long actualTokens) {
        long reserved = Math.max(0, reservation.reservedTokens());
        // 供应商上报的用量不得让 actual_tokens 超过本次调用原子预留的数量。估算值被
        // 刻意作为记账硬上限；分词方式不兼容的供应商不得绕过各层限额。
        // 供应商真实用量另行完整记入 llm_usage，供分析而不参与强制。
        long actual = Math.min(reserved, Math.max(0, actualTokens));
        jdbc.update("""
                UPDATE llm_turn_budget
                SET reserved_tokens=GREATEST(reserved_tokens-?,0), actual_tokens=actual_tokens+?
                WHERE trace_id=?
                """, reserved, actual, reservation.traceId());
        jdbc.update("""
                UPDATE llm_daily_budget
                SET reserved_tokens=GREATEST(reserved_tokens-?,0), actual_tokens=actual_tokens+?
                WHERE budget_day=CURRENT_DATE
                """, reserved, actual);
        if (reservation.background()) {
            jdbc.update("""
                    UPDATE llm_daily_budget
                    SET background_reserved_tokens=GREATEST(background_reserved_tokens-?,0),
                        background_actual_tokens=background_actual_tokens+?
                    WHERE budget_day=CURRENT_DATE
                    """, reserved, actual);
        }
        if (reservation.userId() != null) {
            jdbc.update("""
                    UPDATE llm_user_budget
                    SET reserved_tokens=GREATEST(reserved_tokens-?,0), actual_tokens=actual_tokens+?
                    WHERE user_id=? AND budget_day=CURRENT_DATE
                    """, reserved, actual, reservation.userId());
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
            throw reject(BudgetExhausted.Kind.TURN, "本轮 token 预算已用尽");
        }
    }

    /** 预留前查询归属用户；无归属 (评测/入库等) 返回 null 并跳过用户层检查。 */
    private Long attributedUser(String traceId) {
        try {
            return jdbc.queryForObject(
                    "SELECT user_id FROM llm_turn_budget WHERE trace_id = ?", Long.class, traceId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private void reserveUser(long userId, long amount) {
        try {
            jdbc.queryForObject("""
                    INSERT INTO llm_user_budget (user_id, budget_day, reserved_tokens)
                    VALUES (?, CURRENT_DATE, ?)
                    ON CONFLICT (user_id, budget_day) DO UPDATE
                    SET reserved_tokens = llm_user_budget.reserved_tokens + EXCLUDED.reserved_tokens
                    WHERE llm_user_budget.reserved_tokens + llm_user_budget.actual_tokens
                            + EXCLUDED.reserved_tokens <= ?
                    RETURNING reserved_tokens
                    """, Long.class, userId, amount, props.budget().userDailyTokenLimit());
        } catch (EmptyResultDataAccessException e) {
            throw reject(BudgetExhausted.Kind.USER_DAILY, "用户每日 token 限额已用尽");
        }
    }

    /**
     * 后台任务 (摘要/抽取/批量嵌入) 的子预算：占用全局日限额中划给后台的份额，
     * 保证后台重试风暴不能挤占前台可用额度。压力顺延的快速检查在 reserve 入口。
     */
    private void reserveBackgroundShare(long amount) {
        long share = Math.max(1, props.budget().dailyTokenLimit() * props.budget().backgroundSharePercent() / 100);
        try {
            jdbc.queryForObject("""
                    INSERT INTO llm_daily_budget (budget_day, background_reserved_tokens)
                    VALUES (CURRENT_DATE, ?)
                    ON CONFLICT (budget_day) DO UPDATE
                    SET background_reserved_tokens = llm_daily_budget.background_reserved_tokens
                            + EXCLUDED.background_reserved_tokens
                    WHERE llm_daily_budget.background_reserved_tokens + llm_daily_budget.background_actual_tokens
                            + EXCLUDED.background_reserved_tokens <= ?
                    RETURNING background_reserved_tokens
                    """, Long.class, amount, share);
        } catch (EmptyResultDataAccessException e) {
            throw reject(BudgetExhausted.Kind.BACKGROUND_DEFERRED, "后台 token 预算已用尽，任务顺延");
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
            throw reject(BudgetExhausted.Kind.GLOBAL, "每日 token 限额已用尽");
        }
    }

    private void releaseTurn(String traceId, long amount) {
        try {
            jdbc.update("""
                    UPDATE llm_turn_budget SET reserved_tokens=GREATEST(reserved_tokens-?,0) WHERE trace_id=?
                    """, amount, traceId);
        } catch (RuntimeException rollbackFailure) {
            log.error("failed to roll back turn token reservation trace={}: {}", traceId, rollbackFailure.getMessage());
        }
    }

    private void releaseUser(long userId, long amount) {
        try {
            jdbc.update("""
                    UPDATE llm_user_budget SET reserved_tokens=GREATEST(reserved_tokens-?,0)
                    WHERE user_id=? AND budget_day=CURRENT_DATE
                    """, amount, userId);
        } catch (RuntimeException rollbackFailure) {
            log.error("failed to roll back user token reservation user={}: {}", userId, rollbackFailure.getMessage());
        }
    }

    private void releaseBackgroundShare(long amount) {
        try {
            jdbc.update("""
                    UPDATE llm_daily_budget SET background_reserved_tokens=GREATEST(background_reserved_tokens-?,0)
                    WHERE budget_day=CURRENT_DATE
                    """, amount);
        } catch (RuntimeException rollbackFailure) {
            log.error("failed to roll back background token reservation: {}", rollbackFailure.getMessage());
        }
    }

    private void releaseDay(long amount) {
        try {
            jdbc.update("""
                    UPDATE llm_daily_budget SET reserved_tokens=GREATEST(reserved_tokens-?,0)
                    WHERE budget_day=CURRENT_DATE
                    """, amount);
        } catch (RuntimeException rollbackFailure) {
            log.error("failed to roll back daily token reservation: {}", rollbackFailure.getMessage());
        }
    }

    @Scheduled(cron = "0 10 3 * * *")
    public void purgeBudgetRows() {
        jdbc.update("DELETE FROM llm_turn_budget WHERE created_at < now() - INTERVAL '3 days'");
        jdbc.update("DELETE FROM llm_daily_budget WHERE budget_day < CURRENT_DATE - 30");
        jdbc.update("DELETE FROM llm_user_budget WHERE budget_day < CURRENT_DATE - 30");
    }
}
