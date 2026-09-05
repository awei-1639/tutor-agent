package com.tutor.push;

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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL regression coverage for the push-domain SQL boundaries: notification reads and
 * user-scoped read marking, released-job lookup with fallback, and push-task idempotency.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "runIntegrationTests", matches = "true")
class PushStoresPostgresIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private NotificationStore notifications;
    private CareerJobStore careerJobs;
    private PushJobStore pushJobs;

    @BeforeAll
    void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        notifications = new NotificationStore(jdbc);
        careerJobs = new CareerJobStore(jdbc);
        pushJobs = new PushJobStore(jdbc);
    }

    @BeforeEach
    void clean() {
        jdbc.update("TRUNCATE notifications, push_tasks, resumes, jobs, users RESTART IDENTITY CASCADE");
    }

    @Test
    void listsMarksAndGuardsNotificationsPerUser() {
        long owner = insertUser("owner@example.com");
        long other = insertUser("other@example.com");
        notifications.add(owner, "guide", "{\"step\":1}");
        notifications.add(owner, "job_push", "{\"title\":\"Java\"}");
        notifications.add(owner, "system", "{\"msg\":\"hi\"}");
        notifications.add(other, "guide", "{\"step\":2}");

        long ownerGuideId = idOfType(notifications.list(owner, false), "guide");
        long otherGuideId = idOfType(notifications.list(other, false), "guide");

        List<Map<String, Object>> all = notifications.list(owner, false);
        assertThat(all).hasSize(3);
        assertThat(all.get(0).get("type")).isEqualTo("system");
        assertThat((String) all.get(2).get("payload")).contains("step");
        assertThat(all.get(2).get("read")).isEqualTo(Boolean.FALSE);
        assertThat((String) all.get(2).get("created_at")).isNotBlank();

        assertThat(notifications.list(owner, true)).hasSize(3);

        assertThat(notifications.markRead(owner, List.of(ownerGuideId))).isEqualTo(1);
        assertThat(notifications.list(owner, true)).hasSize(2);
        assertThat(notifications.markRead(owner, List.of())).isZero();
        assertThat(notifications.markRead(owner, List.of(otherGuideId))).isZero();
        assertThat(notifications.list(other, true)).hasSize(1);

        assertThat(notifications.hasUnreadGuide(owner)).isFalse();
        notifications.add(owner, "guide", "{\"step\":3}");
        assertThat(notifications.hasUnreadGuide(owner)).isTrue();
        assertThat(notifications.hasUnreadGuide(other)).isTrue();
    }

    @Test
    void findsReleasedJobsWithTargetFallbackAndArrayMapping() {
        long j1 = insertJob("Java后端工程师", "示例A", "北京", List.of("Java", "SQL"), "node-1", true);
        long j2 = insertJob("前端工程师", "示例B", "上海", null, "node-2", true);
        insertJob("Java架构师", "示例C", "深圳", List.of("Java"), "node-3", false);
        long j4 = insertJob("Java开发工程师", "示例D", "杭州", List.of("Spring"), "node-4", true);
        insertJob("Python工程师", "示例E", "北京", List.of("Python"), "node-5", true);

        CareerJobStore.Job job = careerJobs.findReleasedById(j1);
        assertThat(job.title()).isEqualTo("Java后端工程师");
        assertThat(job.requires()).containsExactly("Java", "SQL");
        assertThat(careerJobs.findReleasedById(j2).requires()).isEmpty();
        assertThatThrownBy(() -> careerJobs.findReleasedById(99999))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(careerJobs.findReleasedForTarget("Java")).extracting(CareerJobStore.Job::id)
                .containsExactly(j1, j4);
        assertThat(careerJobs.findReleasedForTarget("Rust")).extracting(CareerJobStore.Job::id)
                .containsExactly(j1, j2, j4);
        assertThat(careerJobs.findReleasedForTarget("")).extracting(CareerJobStore.Job::id)
                .hasSize(3);
    }

    @Test
    void releasesJobsAndEnforcesPushTaskIdempotency() {
        long u1 = insertUser("push-a@example.com");
        long u2 = insertUser("push-b@example.com");
        long j1 = insertJob("Java后端工程师", "示例A", "北京", List.of("Java"), "node-1", false);
        long j2 = insertJob("前端工程师", "示例B", "上海", List.of("JS"), "node-2", false);
        long j3 = insertJob("Python工程师", "示例C", "北京", List.of("Python"), "node-3", false);
        long j4 = insertJob("数据工程师", "示例D", "广州", List.of("SQL"), "node-4", true);

        assertThat(pushJobs.userIds()).containsExactly(u1, u2);

        assertThat(pushJobs.releaseAvailableJobs(2)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE released AND fetched_at IS NOT NULL", Integer.class))
                .isEqualTo(2);
        assertThat(pushJobs.releaseAvailableJobs(10)).isEqualTo(1);
        assertThat(pushJobs.availableCandidates(u1)).extracting(PushJobStore.Candidate::id)
                .containsExactlyInAnyOrder(j1, j2, j3, j4);

        assertThat(pushJobs.claimPush(u1, j1)).isTrue();
        assertThat(pushJobs.claimPush(u1, j1)).isFalse();
        assertThat(pushJobs.availableCandidates(u1)).extracting(PushJobStore.Candidate::id)
                .containsExactlyInAnyOrder(j2, j3, j4);
        assertThat(pushJobs.availableCandidates(u2)).extracting(PushJobStore.Candidate::id)
                .containsExactlyInAnyOrder(j1, j2, j3, j4);

        pushJobs.recordFailure(u1, j2, "boom");
        assertThat(pushJobs.claimPush(u1, j2)).isFalse();
        assertThat(pushJobs.availableCandidates(u1)).extracting(PushJobStore.Candidate::id)
                .containsExactlyInAnyOrder(j3, j4);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM push_tasks WHERE user_id=? AND job_id=?", String.class, u1, j2))
                .isEqualTo("failed");
    }

    @Test
    void readsLatestResumeEmbeddingAndScoresCosineSimilarity() {
        long u1 = insertUser("embed-a@example.com");
        long u2 = insertUser("embed-b@example.com");
        String oldEmbedding = unitVector(0);
        String newEmbedding = unitVector(1);
        jdbc.update("INSERT INTO resumes(user_id, raw_encrypted, structured, embedding) VALUES (?, pgp_sym_encrypt(?, 'k'), '{}'::jsonb, ?::vector)",
                u1, "旧简历", oldEmbedding);
        jdbc.update("INSERT INTO resumes(user_id, raw_encrypted, structured, embedding) VALUES (?, pgp_sym_encrypt(?, 'k'), '{}'::jsonb, ?::vector)",
                u1, "新简历", newEmbedding);

        assertThat(pushJobs.latestResumeEmbedding(u1)).hasValue(newEmbedding);
        assertThat(pushJobs.latestResumeEmbedding(u2)).isEmpty();

        long jobId = insertJob("Java后端工程师", "示例A", "北京", List.of("Java"), "node-1", true, oldEmbedding);
        assertThat(pushJobs.similarity(newEmbedding, jobId)).isCloseTo(0.0, within(1e-6));
        assertThat(pushJobs.similarity(oldEmbedding, jobId)).isCloseTo(1.0, within(1e-6));
    }

    private long idOfType(List<Map<String, Object>> rows, String type) {
        return rows.stream().filter(r -> type.equals(r.get("type"))).findFirst()
                .map(r -> ((Number) r.get("id")).longValue()).orElseThrow();
    }

    private long insertUser(String email) {
        return jdbc.queryForObject(
                "INSERT INTO users(email, password_hash, name) VALUES (?, 'hash', ?) RETURNING id",
                Long.class, email, "Push User");
    }

    private long insertJob(String title, String company, String city, List<String> requires,
                           String nodeId, boolean released) {
        return insertJob(title, company, city, requires, nodeId, released, null);
    }

    private long insertJob(String title, String company, String city, List<String> requires,
                           String nodeId, boolean released, String embedding) {
        String csv = requires == null ? null : String.join(",", requires);
        return jdbc.queryForObject("""
                INSERT INTO jobs(title, company, city, requires_raw, node_id, released, embedding)
                VALUES (?, ?, ?, string_to_array(?, ',')::text[], ?, ?, ?::vector) RETURNING id""",
                Long.class, title, company, city, csv, nodeId, released, embedding);
    }

    private static String unitVector(int hotIndex) {
        StringBuilder vector = new StringBuilder("[");
        for (int i = 0; i < 1024; i++) {
            if (i > 0) vector.append(',');
            vector.append(i == hotIndex ? "1" : "0");
        }
        return vector.append(']').toString();
    }

    private static org.assertj.core.data.Offset<Double> within(double delta) {
        return org.assertj.core.data.Offset.offset(delta);
    }
}
