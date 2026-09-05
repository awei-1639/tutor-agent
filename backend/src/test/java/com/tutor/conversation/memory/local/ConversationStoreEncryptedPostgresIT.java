package com.tutor.conversation.memory.local;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 加密分支的落库回归。带 citations 的 assistant 消息只在配了 RESUME_ENC_KEY 时走
 * pgp_sym_encrypt 那条 INSERT，而它曾漏掉 citations 的 ::jsonb 转换。用户消息的
 * citations 为 null、不触发类型推断，所以纯单测和无密钥环境都发现不了。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "runIntegrationTests", matches = "true")
class ConversationStoreEncryptedPostgresIT {
    private static final String ENC_KEY = "test-enc-key-32-bytes-minimum-000";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private ConversationStore encrypted;
    private ConversationStore plaintext;

    @BeforeAll
    void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        encrypted = new ConversationStore(jdbc, ENC_KEY);
        plaintext = new ConversationStore(jdbc, "");
    }

    @Test
    @DisplayName("加密分支能写入带 citations 的 assistant 消息")
    void persistsAssistantCitationsOnTheEncryptedPath() {
        long conversationId = insertConversation(insertUser());
        String citations = "[{\"sid\":\"S1\",\"node_id\":\"skill:java-basics\"}]";

        long messageId = encrypted.appendMessage(conversationId, "assistant", "回答正文[S1]",
                "chat", citations, "trace-enc", 12, "verified", "[]", null);

        assertThat(messageId).isPositive();
        assertThat(jdbc.queryForObject(
                "SELECT citations->0->>'node_id' FROM messages WHERE id=?", String.class, messageId))
                .isEqualTo("skill:java-basics");
        // 加密路径必须同时写入密文，明文列只保留掩码投影。
        assertThat(jdbc.queryForObject(
                "SELECT content_encrypted IS NOT NULL FROM messages WHERE id=?", Boolean.class, messageId))
                .isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT pgp_sym_decrypt(content_encrypted, ?) FROM messages WHERE id=?",
                String.class, ENC_KEY, messageId))
                .isEqualTo("回答正文[S1]");
    }

    @Test
    @DisplayName("未配密钥时的明文分支同样能写入 citations")
    void persistsAssistantCitationsOnThePlaintextPath() {
        long conversationId = insertConversation(insertUser());

        long messageId = plaintext.appendMessage(conversationId, "assistant", "回答正文[S2]",
                "chat", "[{\"sid\":\"S2\",\"node_id\":\"skill:spring-boot\"}]",
                "trace-plain", 9, "verified", "[]", null);

        assertThat(jdbc.queryForObject(
                "SELECT citations->0->>'node_id' FROM messages WHERE id=?", String.class, messageId))
                .isEqualTo("skill:spring-boot");
    }

    @Test
    @DisplayName("同一回合的用户提问与助手回答可以共存")
    void keepsBothTurnMessagesForTheSameChatTurn() {
        long userId = insertUser();
        long conversationId = insertConversation(userId);
        String turnId = insertChatTurn(userId, conversationId);

        // 一个 durable turn 要写两条消息：submit() 写提问，completeWithMessage() 写回答。
        // 唯一索引若只按 chat_turn_id，第二条必然冲突，助手回答永远落不了库。
        encrypted.appendMessage(conversationId, "user", "问题正文",
                null, null, "trace-both", 4, null, null, turnId);
        long answerId = encrypted.appendMessage(conversationId, "assistant", "回答正文[S1]",
                "chat", "[{\"sid\":\"S1\"}]", "trace-both", 6, "verified", "[]", turnId);

        assertThat(answerId).isPositive();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE chat_turn_id=?::uuid", Long.class, turnId))
                .isEqualTo(2L);
        // 同一回合同一角色仍必须唯一，否则重试的 worker 会写出重复回答。
        assertThatThrownBy(() -> encrypted.appendMessage(conversationId, "assistant", "重复回答",
                "chat", null, "trace-both", 6, "verified", "[]", turnId))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private long insertUser() {
        return jdbc.queryForObject("INSERT INTO users(email, password_hash, name) VALUES (?, ?, ?) RETURNING id",
                Long.class, "conv-" + UUID.randomUUID() + "@example.com", "hash", "Conv User");
    }

    private long insertConversation(long userId) {
        return jdbc.queryForObject("INSERT INTO conversations(user_id, last_active_at) VALUES (?, now()) RETURNING id",
                Long.class, userId);
    }

    private String insertChatTurn(long userId, long conversationId) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO chat_turns (id, user_id, conversation_id, request_id, question, trace_id)
                VALUES (?::uuid, ?, ?, ?, ?, ?)
                """, id, userId, conversationId, "req-" + id, "问题正文", "trace-both");
        return id;
    }
}
