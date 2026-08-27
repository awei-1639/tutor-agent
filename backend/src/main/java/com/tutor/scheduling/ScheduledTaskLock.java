package com.tutor.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.UUID;

/**
 * 定时任务的分布式互斥锁。多实例部署时，同一 cron 任务在同一触发窗口只应由抢到锁的实例执行。
 * 锁基于 PostgreSQL 原子 UPSERT + 到期时间：持有者在 leaseSeconds 内独占，过期后其它实例可接管，
 * 避免持锁实例崩溃后任务永久停摆。
 *
 * <p>清理、token 记账等幂等任务无需加锁；副作用型任务 (如全量岗位推送) 才需要防重复执行。
 */
@Component
public class ScheduledTaskLock {
    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskLock.class);
    private final JdbcTemplate jdbc;
    private final String owner;

    public ScheduledTaskLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.owner = ManagementFactory.getRuntimeMXBean().getName() + "-" + UUID.randomUUID();
    }

    /**
     * 尝试为 taskName 抢占一个 leaseSeconds 秒的执行窗口。仅当无人持锁或既有锁已过期时成功。
     * 数据库不可用时返回 false，宁可跳过一次副作用型任务，也不在无法协调时重复执行。
     */
    public boolean tryAcquire(String taskName, long leaseSeconds) {
        try {
            Integer updated = jdbc.update("""
                    INSERT INTO scheduled_task_locks (task_name, locked_until, owner)
                    VALUES (?, now() + make_interval(secs => ?), ?)
                    ON CONFLICT (task_name) DO UPDATE SET
                        locked_until = EXCLUDED.locked_until,
                        owner = EXCLUDED.owner
                    WHERE scheduled_task_locks.locked_until <= now()
                    """, taskName, (double) leaseSeconds, owner);
            return updated != null && updated > 0;
        } catch (RuntimeException e) {
            log.warn("定时任务锁存储不可用，跳过本次执行 task={}: {}", taskName, e.getMessage());
            return false;
        }
    }

    /** 便捷方法：抢到锁则执行任务，否则跳过 (说明其它实例已承担)。 */
    public void runIfLeader(String taskName, long leaseSeconds, Runnable task) {
        if (tryAcquire(taskName, leaseSeconds)) {
            task.run();
        } else {
            log.debug("跳过定时任务 task={}，锁由其它实例持有", taskName);
        }
    }
}
