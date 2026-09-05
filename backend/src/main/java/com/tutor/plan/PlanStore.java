package com.tutor.plan;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.tutor.plan.PlanModels.Checkin;
import static com.tutor.plan.PlanModels.Plan;
import static com.tutor.plan.PlanModels.PlanGenerationJob;
import static com.tutor.plan.PlanModels.PlanTask;
import static com.tutor.plan.PlanModels.PlanTaskDraft;

/** PostgreSQL persistence adapter for plans, check-ins, and durable generation jobs. */
@Repository
class PlanStore {
    private final JdbcTemplate jdbc;

    PlanStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long enqueueGeneration(long userId, String goal, String currentSkills,
                           String checkinHistory, String traceId) {
        return jdbc.queryForObject("""
                INSERT INTO plan_generation_jobs
                    (user_id, goal, current_skills, checkin_history, trace_id)
                VALUES (?,?,?,?,?) RETURNING id
                """, Long.class, userId, goal, currentSkills, checkinHistory, traceId);
    }

    Optional<PlanGenerationJob> findGenerationJob(long userId, long jobId) {
        return jdbc.query("""
                SELECT id, status, plan_id, error, created_at, finished_at
                FROM plan_generation_jobs WHERE id=? AND user_id=?
                """, (rs, i) -> new PlanGenerationJob(
                rs.getLong(1), rs.getString(2),
                rs.getObject(3, Long.class), rs.getString(4),
                rs.getTimestamp(5).toInstant(),
                rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant()),
                jobId, userId).stream().findFirst();
    }

    QueuedJob claimNextGenerationJob() {
        return jdbc.query("""
                WITH next_job AS (
                    SELECT id FROM plan_generation_jobs
                    WHERE status='queued'
                       OR (status='running' AND (lease_until IS NULL OR lease_until < now()))
                    ORDER BY id
                    FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE plan_generation_jobs j
                SET status='running', started_at=now(), error=NULL,
                    lease_token=?, lease_until=now() + interval '10 minutes'
                FROM next_job
                WHERE j.id=next_job.id
                RETURNING j.id, j.user_id, j.goal, j.current_skills, j.checkin_history, j.trace_id, j.lease_token
                """, (rs, i) -> new QueuedJob(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class)),
                UUID.randomUUID()).stream().findFirst().orElse(null);
    }

    boolean ownsLease(QueuedJob job) {
        Integer active = jdbc.queryForObject("""
                SELECT count(*) FROM plan_generation_jobs
                WHERE id=? AND status='running' AND lease_token=? AND lease_until > now()
                """, Integer.class, job.id(), job.leaseToken());
        return active != null && active == 1;
    }

    void completeGeneration(QueuedJob job, long planId) {
        jdbc.update("""
                UPDATE plan_generation_jobs
                SET status='completed', plan_id=?, finished_at=now(), lease_token=NULL, lease_until=NULL
                WHERE id=? AND status='running' AND lease_token=? AND lease_until > now()
                """, planId, job.id(), job.leaseToken());
    }

    void failGeneration(QueuedJob job, String error) {
        jdbc.update("""
                UPDATE plan_generation_jobs
                SET status='failed', error=?, finished_at=now(), lease_token=NULL, lease_until=NULL
                WHERE id=? AND status='running' AND lease_token=? AND lease_until > now()
                """, error, job.id(), job.leaseToken());
    }

    Plan saveGeneratedPlan(long userId, String goalSummary, LocalDate monday,
                           List<PlanTaskDraft> tasks) {
        long planId = jdbc.queryForObject(
                "INSERT INTO plans (user_id, goal, week_start, week_end, status) "
                        + "VALUES (?,?,?::date,?::date,?) RETURNING id",
                Long.class, userId, goalSummary,
                Date.valueOf(monday), Date.valueOf(monday.plusDays(6)), "active");

        for (PlanTaskDraft task : tasks) {
            jdbc.update(
                    "INSERT INTO plan_tasks (plan_id, user_id, day, content, kind, related_node_ids, estimated_minutes) "
                            + "VALUES (?,?,?,?,?,?::text[],?)",
                    planId, userId, Date.valueOf(task.day()), task.content(), task.kind(),
                    toTextArray(task.relatedSkills()), task.minutes());
        }
        return new Plan(planId, userId, goalSummary, monday, monday.plusDays(6), "active");
    }

    boolean taskExists(long taskId, long userId) {
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM plan_tasks WHERE id=? AND user_id=?",
                Integer.class, taskId, userId);
        return exists != null && exists > 0;
    }

    Checkin addCheckin(long taskId, long userId, String status, String feedback) {
        long id = jdbc.queryForObject(
                "INSERT INTO checkins (task_id, user_id, status, feedback) VALUES (?,?,?,?) RETURNING id",
                Long.class, taskId, userId, status, feedback);
        return new Checkin(id, taskId, status, feedback);
    }

    PlanProgress progress(long userId) {
        return jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM checkins c
                   JOIN plan_tasks t ON c.task_id=t.id
                   WHERE c.user_id=? AND t.user_id=? AND c.status='done'
                     AND t.day BETWEEN (current_date - 7) AND current_date) AS done,
                  (SELECT count(*) FROM plan_tasks
                   WHERE user_id=? AND day BETWEEN (current_date - 7) AND current_date) AS total
                """, (rs, i) -> new PlanProgress(rs.getLong(1), rs.getLong(2)),
                userId, userId, userId);
    }

    List<PlanTask> todayTasks(long userId) {
        return jdbc.query(
                "SELECT id, plan_id, day, content, kind, estimated_minutes, evidence_hint FROM plan_tasks "
                        + "WHERE user_id=? AND day = current_date ORDER BY id",
                (rs, i) -> new PlanTask(rs.getLong(1), rs.getLong(2),
                        rs.getDate(3).toLocalDate(), rs.getString(4), rs.getString(5),
                        rs.getInt(6), rs.getString(7)),
                userId);
    }

    long activePlanIdOrCreate(long userId, String goal, LocalDate monday, LocalDate sunday) {
        return jdbc.query(
                        "SELECT id FROM plans WHERE user_id=? AND status='active' AND week_start=?::date "
                                + "ORDER BY id DESC LIMIT 1",
                        (rs, i) -> rs.getLong(1), userId, Date.valueOf(monday))
                .stream().findFirst().orElseGet(() -> jdbc.queryForObject(
                        "INSERT INTO plans (user_id, goal, week_start, week_end, status) "
                                + "VALUES (?,?,?,?, 'active') RETURNING id",
                        Long.class, userId, goal, Date.valueOf(monday), Date.valueOf(sunday)));
    }

    boolean hasEvidenceTask(long userId, String skillId, LocalDate from, LocalDate to) {
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM plan_tasks WHERE user_id=? AND "
                        + "related_node_ids @> ?::text[] AND day BETWEEN ?::date AND ?::date",
                Integer.class, userId, toTextArray(List.of(skillId)), Date.valueOf(from), Date.valueOf(to));
        return exists != null && exists > 0;
    }

    PlanTask addEvidenceTask(long planId, long userId, LocalDate day, String skillId,
                             String content, String evidenceHint) {
        return jdbc.queryForObject("""
                INSERT INTO plan_tasks (plan_id, user_id, day, content, kind, related_node_ids, estimated_minutes, evidence_hint)
                VALUES (?,?,?,?,?,?::text[],?,?)
                RETURNING id, plan_id, day, content, kind, estimated_minutes, evidence_hint
                """, (rs, i) -> new PlanTask(rs.getLong(1), rs.getLong(2), rs.getDate(3).toLocalDate(),
                rs.getString(4), rs.getString(5), rs.getInt(6), rs.getString(7)),
                planId, userId, Date.valueOf(day), content, "practice",
                toTextArray(List.of(skillId)), 45, evidenceHint);
    }

    private String toTextArray(List<String> values) {
        return "{" + values.stream()
                .map(value -> "\"" + value.replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",")) + "}";
    }

    record QueuedJob(long id, long userId, String goal, String currentSkills,
                     String checkinHistory, String traceId, UUID leaseToken) {
    }

    record PlanProgress(long done, long total) {
    }
}
