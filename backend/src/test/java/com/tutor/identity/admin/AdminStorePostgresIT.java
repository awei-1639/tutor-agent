package com.tutor.identity.admin;

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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL regression coverage for the admin SQL boundary: overview counts, user lifecycle filters, and audit rows. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "runIntegrationTests", matches = "true")
class AdminStorePostgresIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private AdminStore store;

    @BeforeAll
    void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new AdminStore(jdbc);
    }

    @BeforeEach
    void clean() {
        jdbc.update("""
                TRUNCATE admin_audit_log, interview_feedback, interview_questions,
                          interview_sessions, eval_runs, users RESTART IDENTITY CASCADE""");
    }

    @Test
    void managesUserLifecycleStatusFiltersAndAudit() {
        long admin = insertUser("admin@example.com", "Admin", "ADMIN", minutesAgo(40));
        long active = insertUser("active@example.com", "Active User", "USER", minutesAgo(30));
        long disabled = insertUser("disabled@example.com", "Disabled User", "USER", minutesAgo(20));
        long deleted = insertUser("deleted@example.com", "Deleted User", "USER", minutesAgo(10));
        jdbc.update("UPDATE users SET disabled_at=now() WHERE id=?", disabled);
        jdbc.update("UPDATE users SET deleted_at=now(), disabled_at=now() WHERE id=?", deleted);

        Map<String, Object> overview = store.overviewUsers();
        assertThat(overview.get("total")).isEqualTo(4L);
        assertThat(overview.get("active")).isEqualTo(2L);
        assertThat(overview.get("disabled")).isEqualTo(1L);
        assertThat(overview.get("deleted")).isEqualTo(1L);
        assertThat(overview.get("admins")).isEqualTo(1L);

        assertThat(store.isAdmin(admin)).isTrue();
        assertThat(store.isAdmin(active)).isFalse();

        List<Map<String, Object>> activeUsers = store.users(null, "active", 0, 10);
        assertThat(activeUsers).extracting(u -> u.get("id"))
                .containsExactlyInAnyOrder(admin, active);
        assertThat(activeUsers).allSatisfy(u -> assertThat(u.get("status")).isEqualTo("active"));
        assertThat(store.users(null, "disabled", 0, 10)).extracting(u -> u.get("id"))
                .containsExactly(disabled);
        assertThat(store.users(null, "deleted", 0, 10)).extracting(u -> u.get("id"))
                .containsExactly(deleted);
        assertThat(store.users("active@", null, 0, 10)).extracting(u -> u.get("id"))
                .containsExactly(active);

        List<Map<String, Object>> pageOne = store.users(null, null, 0, 2);
        assertThat(pageOne).extracting(u -> u.get("id")).containsExactly(deleted, disabled);
        List<Map<String, Object>> pageTwo = store.users(null, null, 1, 2);
        assertThat(pageTwo).extracting(u -> u.get("id")).containsExactly(active, admin);
        assertThat(store.userCount(null, null)).isEqualTo(4L);
        assertThat(store.userCount("active@", null)).isEqualTo(1L);

        assertThat(store.disable(active)).isEqualTo(1);
        Timestamp disabledAt = jdbc.queryForObject(
                "SELECT disabled_at FROM users WHERE id=?", Timestamp.class, active);
        store.disable(active);
        assertThat(jdbc.queryForObject("SELECT disabled_at FROM users WHERE id=?", Timestamp.class, active))
                .isEqualTo(disabledAt);
        assertThat(store.disable(deleted)).isZero();

        assertThat(store.restore(active)).isEqualTo(1);
        assertThat(store.users(null, "active", 0, 10)).extracting(u -> u.get("id")).contains(active);
        assertThat(store.softDelete(active)).isEqualTo(1);
        assertThat(store.users(null, "deleted", 0, 10)).extracting(u -> u.get("id")).contains(active);

        store.audit(admin, "disable_user", active, "{\"reason\":\"违规\"}");
        store.audit(admin, "view_overview", null, null);
        List<Map<String, Object>> logs = store.audit(10);
        assertThat(logs).hasSize(2);

        Map<String, Object> disableEntry = entryByAction(logs, "disable_user");
        assertThat(disableEntry.get("adminUserId")).isEqualTo(admin);
        assertThat(disableEntry.get("adminName")).isEqualTo("Admin");
        assertThat(disableEntry.get("targetUserId")).isEqualTo(active);
        assertThat(disableEntry.get("targetName")).isEqualTo("Active User");
        assertThat((String) disableEntry.get("metadata")).contains("违规");

        Map<String, Object> viewEntry = entryByAction(logs, "view_overview");
        assertThat(viewEntry.get("targetUserId")).isNull();
        assertThat(viewEntry.get("targetName")).isNull();
        assertThat(viewEntry.get("metadata")).isEqualTo("{}");
    }

    @Test
    void aggregatesEvalRunsAndInterviewMetrics() {
        long interviewee = insertUser("interviewee@example.com", "Interviewee", "USER", minutesAgo(30));
        String sessionId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO interview_sessions(id, user_id, target_role, topic, status,
                                               current_question_sequence, main_question_count, deadline_at, completed_at)
                VALUES (?, ?, '', '后端面试', 'COMPLETED', 1, 1, now(), now())""", sessionId, interviewee);
        jdbc.update("""
                INSERT INTO interview_questions(session_id, sequence, kind, prompt, score, scorecard)
                VALUES (?, 1, 'MAIN', '讲讲数据库索引', 8, ?::jsonb)""",
                sessionId, "{\"confidence\": 8}");
        jdbc.update("""
                INSERT INTO interview_feedback(user_id, session_id, rating, reason)
                VALUES (?, ?, 'inaccurate', '评分偏低')""", interviewee, sessionId);

        insertEvalRun("run-old", "v1", 20, minutesAgo(20));
        insertEvalRun("run-new", "v2", 35, minutesAgo(10));

        Map<String, Object> metrics = store.interviewMetrics();
        assertThat(metrics.get("finalized_sessions")).isEqualTo(1L);
        assertThat(metrics.get("total_feedback")).isEqualTo(1L);
        assertThat(metrics.get("inaccurate_feedback")).isEqualTo(1L);
        assertThat(((Number) metrics.get("avg_confidence")).doubleValue()).isEqualTo(8.0);

        List<Map<String, Object>> runs = store.recentEvalRuns();
        assertThat(runs).hasSize(2);
        assertThat(runs.get(0).get("datasetVersion")).isEqualTo("v2");
        assertThat(runs.get(0).get("status")).isEqualTo("completed");
        assertThat(runs.get(0).get("totalCases")).isEqualTo(35);
        assertThat(runs.get(1).get("datasetVersion")).isEqualTo("v1");

        List<Map<String, Object>> calibration = store.recentCalibration();
        assertThat(calibration).hasSize(1);
        assertThat(calibration.get(0).get("rating")).isEqualTo("inaccurate");
        assertThat(calibration.get(0).get("reason")).isEqualTo("评分偏低");
    }

    private Map<String, Object> entryByAction(List<Map<String, Object>> logs, String action) {
        return logs.stream().filter(l -> action.equals(l.get("action"))).findFirst().orElseThrow();
    }

    private long insertUser(String email, String name, String role, Instant createdAt) {
        return jdbc.queryForObject(
                "INSERT INTO users(email, password_hash, name, role, created_at) VALUES (?, 'hash', ?, ?, ?) RETURNING id",
                Long.class, email, name, role, Timestamp.from(createdAt));
    }

    private void insertEvalRun(String sha, String datasetVersion, int totalCases, Instant createdAt) {
        jdbc.update("""
                INSERT INTO eval_runs(git_sha, mode, model_config, metrics, status, dataset_version,
                                      top_k, total_cases, started_at, finished_at, created_at)
                VALUES (?, 'fused', '{}'::jsonb, '{}'::jsonb, 'completed', ?, 5, ?, ?, ?, ?)""",
                sha, datasetVersion, totalCases, Timestamp.from(createdAt.plusSeconds(60)),
                Timestamp.from(createdAt.plusSeconds(120)), Timestamp.from(createdAt));
    }

    private static Instant minutesAgo(long minutes) {
        return Instant.now().minusSeconds(minutes * 60);
    }
}
