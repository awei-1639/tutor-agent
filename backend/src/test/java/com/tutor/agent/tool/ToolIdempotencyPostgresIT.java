package com.tutor.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "runIntegrationTests", matches = "true")
class ToolIdempotencyPostgresIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private JdbcToolIdempotencyStore idempotency;
    private JdbcToolCallAuditor auditor;

    @BeforeAll
    void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        idempotency = new JdbcToolIdempotencyStore(jdbc, new ObjectMapper());
        auditor = new JdbcToolCallAuditor(jdbc);
    }

    @BeforeEach
    void clean() {
        jdbc.update("TRUNCATE tool_idempotency, tool_calls, users RESTART IDENTITY CASCADE");
    }

    @Test
    void claimsOnceAndReplaysCompletedResult() {
        long userId = insertUser();

        assertThat(idempotency.claim(userId, "push_run", "key-1")).isTrue();
        assertThat(idempotency.claim(userId, "push_run", "key-1")).isFalse();
        idempotency.complete(userId, "push_run", "key-1", Map.of("pushed", 2));

        assertThat(idempotency.completed(userId, "push_run", "key-1"))
                .contains(Map.of("pushed", 2));
    }

    @Test
    void reclaimsStaleRunningCallAndPersistsAuditDigest() {
        long userId = insertUser();
        assertThat(idempotency.claim(userId, "resume_upload", "key-2")).isTrue();
        jdbc.update("UPDATE tool_idempotency SET updated_at=now() - interval '11 minutes' WHERE user_id=?", userId);

        idempotency.reclaimExpired(Duration.ofMinutes(10));
        assertThat(idempotency.claim(userId, "resume_upload", "key-2")).isTrue();

        auditor.record(new ToolCallRecord("trace-1", "resume", "resume_upload", "digest", "success", "L1", 42, "key-2"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tool_calls WHERE trace_id='trace-1' AND idempotency_key='key-2'", Integer.class))
                .isEqualTo(1);
    }

    private long insertUser() {
        return jdbc.queryForObject("INSERT INTO users(email, password_hash, name) VALUES (?, ?, ?) RETURNING id",
                Long.class, "tool-" + System.nanoTime() + "@example.com", "hash", "Tool User");
    }
}
