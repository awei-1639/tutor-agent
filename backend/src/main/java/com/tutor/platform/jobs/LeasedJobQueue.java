package com.tutor.platform.jobs;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 持久化租约队列的 SQL 内核：领取 (FOR UPDATE SKIP LOCKED)、租约围栏、续租、租约耗尽回收。
 *
 * <p>仓内有多张"队列即业务表"的持久化任务表（chat_turns、plan_generation_jobs、
 * interview_turn_jobs、interview_completion_jobs、knowledge_ingestion_jobs、
 * knowledge_oss_cleanup_jobs、memory_sync_outbox）。它们各自手写过一份大同小异的
 * 领取/围栏 SQL，且已经漂移出真实缺陷（有的缺围栏 token、有的缺重试上限、有的缺
 * 心跳续租）。这些表承载业务状态与用户可见进度，不能重建为一张通用任务表，因此
 * 统一方式是：本类拥有全部与业务无关的队列机制 SQL 模板，各 store 只保留自己的
 * enqueue 幂等、业务列回写（SET 片段）与 RETURNING 列。</p>
 *
 * <p>约定：各表的队列机制列统一为 {@code status}（运行中取 {@link LeaseTable#runningStatus()}）、
 * {@code lease_token}（UUID）、{@code lease_until}（TIMESTAMPTZ）。业务列（结果回写、
 * 退避列、幂等键）由各 store 通过 SQL 片段自带，片段中以 {@code j} 引用目标表别名
 * （{@code j.attempts}），id 绑定方式由 {@link LeaseTable#idPredicate()} 描述
 * （uuid 列用 {@code id=?::uuid}，其余用 {@code id=?}）。</p>
 */
@Component
public class LeasedJobQueue {
    private final JdbcTemplate jdbc;

    public LeasedJobQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一张租约任务表的机制参数。 */
    public record LeaseTable(String table, String runningStatus, String completedStatus, String idPredicate) {
        public static LeaseTable of(String table, String runningStatus, String completedStatus, String idPredicate) {
            return new LeaseTable(table, runningStatus, completedStatus, idPredicate);
        }
    }

    /**
     * 领取下一条可执行任务：内部 CTE 按给定条件与排序选一行并加行锁
     * （{@code FOR UPDATE SKIP LOCKED}），随后原子地套用 {@code setSql}（通常是把
     * status 置为 running、attempts+1、写新租约）并按 {@code returning} 返回。
     *
     * @param claimWhere 可领取条件，如 {@code "(status='PENDING' AND next_attempt_at <= now())"}
     *                   {@code " OR (status='RUNNING' AND lease_until < now() AND attempts < 3)"}；
     *                   引用列时不要带别名（位于 CTE 内的裸表扫描）
     * @param setSql     完整 SET 主体，引用目标表用别名 {@code j}（如 {@code j.attempts}）
     * @param orderBy    候选排序，如 {@code created_at} 或 {@code id}
     * @param args       {@code setSql} 中的占位符实参，按出现顺序排列
     */
    public <T> Optional<T> claimNext(LeaseTable leaseTable, String claimWhere, String setSql,
                                     String orderBy, String returning, RowMapper<T> mapper, Object... args) {
        String sql = """
                WITH candidate AS (
                  SELECT id FROM %s
                  WHERE %s
                  ORDER BY %s FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE %s j SET %s
                FROM candidate WHERE j.id=candidate.id
                RETURNING %s
                """.formatted(leaseTable.table(), claimWhere, orderBy, leaseTable.table(), setSql, returning);
        return jdbc.query(sql, mapper, args).stream().findFirst();
    }

    /** 当前租约是否仍有效（行处于 running、token 匹配且未过期）。 */
    public boolean owns(LeaseTable leaseTable, Object id, UUID leaseToken) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM %s
                WHERE %s AND status='%s' AND lease_token=? AND lease_until > now()
                """.formatted(leaseTable.table(), leaseTable.idPredicate(), leaseTable.runningStatus()),
                Integer.class, id, leaseToken);
        return count != null && count == 1;
    }

    /** 续租：仅当围栏仍有效时把 lease_until 顺延 leaseSeconds。返回是否续租成功。 */
    public boolean renew(LeaseTable leaseTable, Object id, UUID leaseToken, long leaseSeconds) {
        return jdbc.update("""
                UPDATE %s SET lease_until=now() + (? * interval '1 second')
                WHERE %s AND status='%s' AND lease_token=? AND lease_until > now()
                """.formatted(leaseTable.table(), leaseTable.idPredicate(), leaseTable.runningStatus()),
                leaseSeconds, id, leaseToken) == 1;
    }

    /**
     * 围栏内的完成：置 completedStatus、清空租约，并套用业务回写片段 {@code extraSet}
     * （以逗号开头，如 {@code ", finished_at=now(), updated_at=now(), answer_message_id=?"}）。
     * 返回是否仍持有围栏（false 意味着租约已被接管或任务已不在 running）。
     */
    public boolean complete(LeaseTable leaseTable, Object id, UUID leaseToken, String extraSet, Object... extraArgs) {
        Object[] args = append(extraArgs, id, leaseToken);
        return jdbc.update("""
                UPDATE %s SET status='%s', lease_token=NULL, lease_until=NULL%s
                WHERE %s AND status='%s' AND lease_token=? AND lease_until > now()
                """.formatted(leaseTable.table(), leaseTable.completedStatus(), extraSet,
                        leaseTable.idPredicate(), leaseTable.runningStatus()),
                args) == 1;
    }

    /**
     * 围栏内的失败：目标状态由调用方给定（重试态或终态），清空租约并套用业务片段
     * {@code extraSet}（错误列、退避列等；以逗号开头，可为空串）。
     */
    public boolean fail(LeaseTable leaseTable, Object id, UUID leaseToken,
                        String setStatus, String extraSet, Object... extraArgs) {
        Object[] args = new Object[extraArgs.length + 3];
        args[0] = setStatus;
        System.arraycopy(extraArgs, 0, args, 1, extraArgs.length);
        args[extraArgs.length + 1] = id;
        args[extraArgs.length + 2] = leaseToken;
        return jdbc.update("""
                UPDATE %s SET status=?, lease_token=NULL, lease_until=NULL%s
                WHERE %s AND status='%s' AND lease_token=? AND lease_until > now()
                """.formatted(leaseTable.table(), extraSet, leaseTable.idPredicate(), leaseTable.runningStatus()),
                args) == 1;
    }

    /**
     * 租约耗尽回收：把 running 且租约已过期、达到重试上限的行批量置为终态。
     * 参数顺序与 SQL 占位符顺序一致：先 {@code setSql} 的实参，后
     * {@code attemptsPredicate} 的实参（如 {@code "attempts >= ?"} 配 {@code maxAttempts}）。
     */
    public int expireExhausted(LeaseTable leaseTable, String setSql, Object[] setArgs,
                               String attemptsPredicate, Object... attemptsArgs) {
        Object[] args = new Object[setArgs.length + attemptsArgs.length];
        System.arraycopy(setArgs, 0, args, 0, setArgs.length);
        System.arraycopy(attemptsArgs, 0, args, setArgs.length, attemptsArgs.length);
        return jdbc.update("""
                UPDATE %s SET %s
                WHERE status='%s' AND lease_until < now() AND %s
                """.formatted(leaseTable.table(), setSql, leaseTable.runningStatus(), attemptsPredicate),
                args);
    }

    private static Object[] append(Object[] head, Object first, Object second) {
        Object[] args = new Object[head.length + 2];
        System.arraycopy(head, 0, args, 0, head.length);
        args[head.length] = first;
        args[head.length + 1] = second;
        return args;
    }
}
