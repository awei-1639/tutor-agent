package com.tutor.memory.local;

import com.tutor.memory.policy.MemoryConsentStore;
import com.tutor.resume.PiiMasker;
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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * PostgreSQL regression coverage for the memory SQL boundaries: clarification/summary/watermark
 * state, the episode lifecycle with summary encryption and key rotation, and external-memory
 * consent generation fencing.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "runIntegrationTests", matches = "true")
class MemoryStoresPostgresIT {
    private static final String ENC_KEY = "it-memory-key";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private ConversationStore conversations;
    private EpisodeStore episodes;

    @BeforeAll
    void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        conversations = new ConversationStore(jdbc, ENC_KEY);
        episodes = new EpisodeStore(jdbc, ENC_KEY);
    }

    @BeforeEach
    void clean() {
        jdbc.update("TRUNCATE episodes, conversations, messages, users RESTART IDENTITY CASCADE");
    }

    @Test
    void tracksClarificationWatermarkAndFencedEncryptedSummary() {
        long user = insertUser();
        long conv = conversations.ensureConversation(null, user);

        assertThat(conversations.clarificationState(conv).pending()).isFalse();

        Instant future = Instant.now().plusSeconds(300);
        conversations.setClarificationPending(conv, "career_gap", future);
        ConversationStore.ClarificationState pending = conversations.clarificationState(conv);
        assertThat(pending.pending()).isTrue();
        assertThat(pending.intent()).isEqualTo("career_gap");
        assertThat(pending.expiresAt()).isNotNull();

        conversations.setClarificationPending(conv, "stale", Instant.now().minusSeconds(60));
        assertThat(conversations.clarificationState(conv).pending()).isFalse();

        conversations.setClarificationPending(conv, "active", future);
        conversations.clearClarification(conv);
        ConversationStore.ClarificationState cleared = conversations.clarificationState(conv);
        assertThat(cleared.pending()).isFalse();
        assertThat(cleared.intent()).isNull();
        assertThat(cleared.expiresAt()).isNull();

        for (int i = 1; i <= 5; i++) {
            conversations.appendMessage(conv, "user", "消息" + i, null, null, 10);
        }
        assertThat(conversations.episodeUptoMsgId(conv)).isZero();
        conversations.advanceEpisodeWatermark(conv, 3);
        conversations.advanceEpisodeWatermark(conv, 1);
        assertThat(conversations.episodeUptoMsgId(conv)).isEqualTo(3);

        assertThat(conversations.messagesAfter(conv, 2, 10))
                .extracting(m -> m.content).containsExactly("消息3", "消息4", "消息5");
        assertThat(conversations.maxFoldableMsgId(conv, 2)).isEqualTo(3);
        assertThat(conversations.messagesToFold(conv, 0, 2))
                .extracting(m -> m.content).containsExactly("消息1", "消息2", "消息3");

        conversations.saveSummary(conv, "总结:user@example.com 想学 Java", 3);
        ConversationStore.SummaryState state = conversations.summaryState(conv);
        assertThat(state.uptoMsgId()).isEqualTo(3);
        assertThat(state.summary()).contains("user@example.com");
        String plaintextColumn = jdbc.queryForObject(
                "SELECT summary FROM conversations WHERE id=?", String.class, conv);
        assertThat(plaintextColumn).contains("EMAIL").doesNotContain("user@example.com");

        jdbc.update("UPDATE users SET memory_generation=5 WHERE id=?", user);
        assertThat(conversations.saveSummaryIfGeneration(conv, user, 4, "过期代总结", 4)).isFalse();
        assertThat(conversations.summaryState(conv).uptoMsgId()).isEqualTo(3);
        assertThat(conversations.saveSummaryIfGeneration(conv, user, 5, "新代总结", 5)).isTrue();
        assertThat(conversations.summaryState(conv).uptoMsgId()).isEqualTo(5);
        assertThat(conversations.summaryState(conv).summary()).contains("新代总结");
    }

    @Test
    void managesEpisodeLifecycleWithEncryptionRotationAndScoping() {
        long user = insertUser();
        long other = insertUser();
        long conv = conversations.ensureConversation(null, user);
        float[] vecA = unitVector(0);
        float[] vecB = unitVector(1);

        long id1 = episodes.insert(user, conv, "总结一",
                List.of("Java", "并发"), List.of("复习AQS"), vecA);
        assertThat(jdbc.queryForObject(
                "SELECT summary_encryption_key_id FROM episodes WHERE id=?", String.class, id1))
                .isEqualTo("v1");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM episodes WHERE id=? AND summary_encrypted IS NOT NULL",
                Integer.class, id1)).isEqualTo(1);

        List<EpisodeStore.Episode> hits = episodes.searchByEmbedding(user, vecA, 5);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).id()).isEqualTo(id1);
        assertThat(hits.get(0).summary()).isEqualTo("总结一");
        assertThat(hits.get(0).relevance()).isCloseTo(1.0, within(1e-6));
        // 加密路径上 topics/open_items 走 PII 脱敏, 明文列只留掩码值
        String maskedTopic = PiiMasker.mask("并发").masked();
        String maskedItem = PiiMasker.mask("复习AQS").masked();
        assertThat(hits.get(0).topics()).containsExactly("Java", maskedTopic);
        assertThat(episodes.searchByEmbedding(user, vecB, 5)).isEmpty();

        assertThat(episodes.recentByUser(user, 10)).extracting(EpisodeStore.Episode::id).contains(id1);
        assertThat(episodes.openItemsByUser(user, 10)).contains(maskedItem);
        assertThat(episodes.activeByUser(user, 5)).hasSize(1);
        assertThat(episodes.activeByUser(user, 5).get(0).topics()).containsExactly("Java", maskedTopic);

        long id2 = episodes.insertIfAbsentReturningId(user, conv, "总结二",
                List.of("SQL"), List.of("练习连接池"), vecB, 10, 20, 0);
        assertThat(id2).isPositive();
        assertThat(episodes.insertIfAbsentReturningId(user, conv, "总结二重复",
                List.of(), List.of(), null, 10, 20, 0)).isZero();

        episodes.recordRemoteMemoryId(id2, user, "remote-1");
        assertThat(episodes.remoteMemoryIdById(id2, user)).hasValue("remote-1");
        assertThat(episodes.remoteMemoryIdById(id2, other)).isEmpty();
        assertThat(episodes.isActiveById(id2, user)).isTrue();

        EpisodeStore rotated = new EpisodeStore(jdbc, "rot-key", "v2", ENC_KEY, "v1");
        long id3 = rotated.insert(user, conv, "总结三",
                List.of("Spring"), List.of("读事务文档"), vecA);
        assertThat(jdbc.queryForObject(
                "SELECT summary_encryption_key_id FROM episodes WHERE id=?", String.class, id3))
                .isEqualTo("v2");
        List<EpisodeStore.Episode> viaRotated = rotated.searchByEmbedding(user, vecA, 5);
        assertThat(viaRotated).extracting(EpisodeStore.Episode::id).containsExactlyInAnyOrder(id1, id3);
        assertThat(viaRotated).extracting(EpisodeStore.Episode::summary)
                .contains("总结一", "总结三");

        assertThat(episodes.deleteByIdForUser(id1, other)).isFalse();
        assertThat(episodes.deleteByIdForUser(id1, user)).isTrue();
        assertThat(episodes.isActiveById(id1, user)).isFalse();
        assertThat(rotated.searchByEmbedding(user, vecA, 5))
                .extracting(EpisodeStore.Episode::id).containsExactly(id3);

        jdbc.update("UPDATE episodes SET expires_at = now() - interval '1 day' WHERE id IN (?, ?)", id2, id3);
        assertThat(episodes.activeByUser(user, 5)).isEmpty();
        assertThat(episodes.recentByUser(user, 5)).isEmpty();
        assertThat(episodes.openItemsByUser(user, 10)).isEmpty();
        assertThat(episodes.isActiveById(id2, user)).isFalse();
        assertThat(episodes.remoteMemoryIdById(id2, user)).isEmpty();
    }

    @Test
    void fencesExternalMemoryConsentWithGenerationBumps() {
        MemoryConsentStore consent = new MemoryConsentStore(jdbc);
        long user = insertUser();

        assertThat(consent.enabledFor(user)).isFalse();
        assertThat(consent.currentGeneration(user)).isZero();

        assertThat(consent.setEnabled(user, true)).isEqualTo(1);
        assertThat(consent.enabledFor(user)).isTrue();
        assertThat(consent.currentGeneration(user)).isEqualTo(1);

        consent.setEnabled(user, true);
        assertThat(consent.currentGeneration(user)).isEqualTo(1);

        consent.setEnabled(user, false);
        assertThat(consent.currentGeneration(user)).isEqualTo(1);
        consent.setEnabled(user, true);
        assertThat(consent.currentGeneration(user)).isEqualTo(2);

        consent.incrementGeneration(user);
        assertThat(consent.currentGeneration(user)).isEqualTo(3);
    }

    private long insertUser() {
        return jdbc.queryForObject("INSERT INTO users DEFAULT VALUES RETURNING id", Long.class);
    }

    private static float[] unitVector(int hotIndex) {
        float[] vector = new float[1024];
        vector[hotIndex] = 1f;
        return vector;
    }
}
