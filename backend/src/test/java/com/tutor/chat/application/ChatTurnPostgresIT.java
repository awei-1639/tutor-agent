package com.tutor.chat.application;

import com.tutor.memory.local.ConversationStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Database-level regression coverage for chat single-flight and fencing. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "runIntegrationTests", matches = "true")
class ChatTurnPostgresIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private ConversationStore conversations;
    private ChatTurnService turns;
    private SimpleMeterRegistry metrics;

    @BeforeAll
    void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        conversations = new ConversationStore(jdbc);
        metrics = new SimpleMeterRegistry();
        turns = new ChatTurnService(jdbc, conversations, mock(ObjectProvider.class), metrics);
    }

    @AfterAll
    void close() {
        if (turns != null) turns.shutdown();
        if (metrics != null) metrics.close();
    }

    @Test
    void enforcesOneActiveConversationTurnAndReplaysSameRequest() {
        long userId = insertUser();
        long conversationId = insertConversation(userId);
        ChatTurnService.Turn first = turns.submit(userId, conversationId, "request-1", "问题", "trace-1");
        ChatTurnService.Turn replay = turns.submit(userId, conversationId, "request-1", "问题", "trace-1");

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE chat_turn_id=?::uuid", Long.class, first.id()))
                .isEqualTo(1L);
        assertThatThrownBy(() -> turns.submit(userId, conversationId, "request-2", "另一个问题", "trace-2"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("正在处理");
    }

    @Test
    void cancellationFencesLateCompletion() {
        long userId = insertUser();
        long conversationId = insertConversation(userId);
        ChatTurnService.Turn turn = turns.submit(userId, conversationId, "request-cancel", "问题", "trace-cancel");
        UUID lease = UUID.randomUUID();
        jdbc.update("UPDATE chat_turns SET status='RUNNING', lease_token=?, lease_until=now()+interval '2 minutes' WHERE id=?::uuid",
                lease, turn.id());

        assertThat(turns.cancel(userId, turn.id()).status()).isEqualTo("CANCELLED");
        ChatTurnService.Claim claim = new ChatTurnService.Claim(turn.id(), userId, conversationId,
                "问题", "trace-cancel", 1, lease);
        assertThat(turns.completeWithMessage(claim, "迟到的回答", "chat", null, 2,
                "not_applicable", "[]")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE chat_turn_id=?::uuid AND role='assistant'",
                Long.class, turn.id())).isEqualTo(0L);
    }

    private long insertUser() {
        return jdbc.queryForObject("INSERT INTO users(email, password_hash, name) VALUES (?, ?, ?) RETURNING id",
                Long.class, "chat-" + UUID.randomUUID() + "@example.com", "hash", "Chat User");
    }

    private long insertConversation(long userId) {
        return jdbc.queryForObject("INSERT INTO conversations(user_id, last_active_at) VALUES (?, now()) RETURNING id",
                Long.class, userId);
    }
}
