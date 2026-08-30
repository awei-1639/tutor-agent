package com.tutor.llm;

import com.tutor.config.LlmProperties;
import com.tutor.context.BudgetPressureView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 全局日预算水位的轻量快照 (≤30s 陈旧)，用于在到达硬上限之前分级降级：
 * NORMAL → ELEVATED (砍多跳/扇出/输出上限) → SEVERE (后台顺延、证据保底减半)
 * → EXHAUSTED (前台拒绝由硬限额执行)。
 * 查询失败按 NORMAL 处理：可用性优先；硬上限仍由 LlmBudgetGuard 的原子预留保证。
 */
@Component
public class BudgetPressureService implements BudgetPressureView {
    private static final Logger log = LoggerFactory.getLogger(BudgetPressureService.class);
    private static final long REFRESH_MS = 30_000;
    private static final int ELEVATED_PERCENT = 80;
    private static final int SEVERE_PERCENT = 95;

    public enum Level { NORMAL, ELEVATED, SEVERE, EXHAUSTED }

    public record Snapshot(Level level, int usedPercent) {}

    private final JdbcTemplate jdbc;
    private final LlmProperties props;
    private volatile Snapshot cached;
    private volatile long refreshedAt;

    public BudgetPressureService(JdbcTemplate jdbc, LlmProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public Snapshot snapshot() {
        Snapshot snapshot = cached;
        long now = System.currentTimeMillis();
        if (snapshot != null && now - refreshedAt < REFRESH_MS) return snapshot;
        synchronized (this) {
            if (cached != null && System.currentTimeMillis() - refreshedAt < REFRESH_MS) return cached;
            cached = refresh();
            refreshedAt = System.currentTimeMillis();
            return cached;
        }
    }

    public Level level() { return snapshot().level(); }

    private Snapshot refresh() {
        try {
            Long used = jdbc.queryForObject("""
                    SELECT COALESCE(reserved_tokens + actual_tokens, 0)
                    FROM llm_daily_budget WHERE budget_day = CURRENT_DATE
                    """, Long.class);
            long limit = Math.max(1, props.budget().dailyTokenLimit());
            int percent = used == null ? 0 : (int) Math.min(100, Math.max(0, used * 100 / limit));
            return new Snapshot(levelFor(percent), percent);
        } catch (RuntimeException e) {
            log.warn("budget pressure snapshot unavailable, assuming NORMAL: {}", e.getMessage());
            return new Snapshot(Level.NORMAL, 0);
        }
    }

    private static Level levelFor(int percent) {
        if (percent >= 100) return Level.EXHAUSTED;
        if (percent >= SEVERE_PERCENT) return Level.SEVERE;
        if (percent >= ELEVATED_PERCENT) return Level.ELEVATED;
        return Level.NORMAL;
    }

    /** ELEVATED 起停用多跳检索升级 (judge 调用)，保住直答预算。 */
    public boolean multiHopAllowed() { return level() == Level.NORMAL; }

    /** ELEVATED 起专家扇出封顶 1 个。 */
    public int maxExperts() { return level() == Level.NORMAL ? Integer.MAX_VALUE : 1; }

    /** SEVERE 起后台任务全部顺延 (硬检查在 LlmBudgetGuard.reserve)。 */
    public boolean backgroundAllowed() {
        Level level = level();
        return level != Level.SEVERE && level != Level.EXHAUSTED;
    }

    /** ELEVATED 起聊天输出上限收紧到 1000，缩短占用窗口。 */
    public int chatOutputCap(int configured) {
        return level() == Level.NORMAL ? configured : Math.min(configured, 1_000);
    }

    @Override public boolean severePressure() { return level() == Level.SEVERE || level() == Level.EXHAUSTED; }
}
