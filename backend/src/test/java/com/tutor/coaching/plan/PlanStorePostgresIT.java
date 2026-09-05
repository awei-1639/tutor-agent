package com.tutor.coaching.plan;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL regression coverage for plan persistence, queue leases, and user scoping. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "runIntegrationTests", matches = "true")
class PlanStorePostgresIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private PlanStore store;

    @BeforeAll
    void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new PlanStore(jdbc);
    }

    @BeforeEach
    void clean() {
        jdbc.update("TRUNCATE plan_generation_jobs, checkins, plan_tasks, plans, users RESTART IDENTITY CASCADE");
    }

    @Test
    void preservesQueueFencingPlanTasksAndCheckins() {
        long userId = insertUser();
        long jobId = store.enqueueGeneration(userId, "后端岗位", "Java", "", "trace-plan");

        assertThat(store.findGenerationJob(userId, jobId)).isPresent();
        assertThat(store.findGenerationJob(userId + 1, jobId)).isEmpty();

        PlanStore.QueuedJob job = store.claimNextGenerationJob();
        assertThat(job).isNotNull();
        assertThat(job.id()).isEqualTo(jobId);
        assertThat(job.userId()).isEqualTo(userId);
        assertThat(store.ownsLease(job)).isTrue();

        LocalDate today = LocalDate.now();
        PlanModels.Plan plan = store.saveGeneratedPlan(
                userId,
                "后端学习计划",
                today.minusDays(today.getDayOfWeek().getValue() - 1L),
                List.of(new PlanModels.PlanTaskDraft(
                        today, "完成 Java 并发练习", "practice", List.of("skill:java"), 45)));
        store.completeGeneration(job, plan.id());

        PlanModels.PlanGenerationJob completed = store.findGenerationJob(userId, jobId).orElseThrow();
        assertThat(completed.status()).isEqualTo("completed");
        assertThat(completed.planId()).isEqualTo(plan.id());

        List<PlanModels.PlanTask> todayTasks = store.todayTasks(userId);
        assertThat(todayTasks).hasSize(1);
        PlanModels.PlanTask task = todayTasks.getFirst();
        assertThat(store.taskExists(task.id(), userId)).isTrue();
        assertThat(store.taskExists(task.id(), userId + 1)).isFalse();
        assertThat(store.hasEvidenceTask(userId, "skill:java", today, today)).isTrue();

        PlanModels.Checkin checkin = store.addCheckin(task.id(), userId, "done", "已提交示例");
        assertThat(checkin.taskId()).isEqualTo(task.id());
        assertThat(store.progress(userId)).isEqualTo(new PlanStore.PlanProgress(1, 1));
    }

    private long insertUser() {
        return jdbc.queryForObject(
                "INSERT INTO users(email, password_hash, name) VALUES (?, ?, ?) RETURNING id",
                Long.class, "plan-" + UUID.randomUUID() + "@example.com", "hash", "Plan User");
    }
}
