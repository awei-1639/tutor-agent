package com.tutor.identity.resume;

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

import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL regression coverage for encrypted resume persistence and per-user structured projections. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "runIntegrationTests", matches = "true")
class ResumeStorePostgresIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private ResumeStore store;

    @BeforeAll
    void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new ResumeStore(jdbc);
    }

    @BeforeEach
    void clean() {
        jdbc.update("TRUNCATE pii_mappings, resumes, users RESTART IDENTITY CASCADE");
    }

    @Test
    void roundTripsEncryptedResumeAndKeepsPerUserLatestProjection() {
        long userA = insertUser("resume-a@example.com");
        long userB = insertUser("resume-b@example.com");
        String key = "it-resume-key";
        String embedding = "[" + "0.1,".repeat(1023) + "0.1" + "]";

        long firstId = store.insert(userA, "张三的简历原文", key, "{\"skills\":[\"Java\"]}", embedding);
        store.insert(userA, "张三的第二份简历原文", key, "{\"skills\":[\"Spring\"]}", embedding);
        store.insert(userB, "李四的简历原文", key, "{\"skills\":[\"Python\"]}", null);

        assertThat(store.latestStructuredJson(userA)).hasValueSatisfying(json ->
                assertThat(json).contains("Spring").doesNotContain("Python"));
        assertThat(store.latestStructuredJson(userB)).hasValueSatisfying(json ->
                assertThat(json).contains("Python").doesNotContain("Java"));
        assertThat(store.latestStructuredJson(userA + 9999)).isEmpty();

        String decrypted = jdbc.queryForObject(
                "SELECT pgp_sym_decrypt(raw_encrypted, ?) FROM resumes WHERE id=?",
                String.class, key, firstId);
        assertThat(decrypted).isEqualTo("张三的简历原文");

        store.savePiiMapping(userA, "{\"NAME_1\":\"张三\"}", key);
        String mapping = jdbc.queryForObject(
                "SELECT pgp_sym_decrypt(mapping_encrypted, ?) FROM pii_mappings WHERE user_id=?",
                String.class, key, userA);
        assertThat(mapping).isEqualTo("{\"NAME_1\":\"张三\"}");
    }

    private long insertUser(String email) {
        return jdbc.queryForObject(
                "INSERT INTO users(email, password_hash, name) VALUES (?, 'hash', ?) RETURNING id",
                Long.class, email, "Resume User");
    }
}
