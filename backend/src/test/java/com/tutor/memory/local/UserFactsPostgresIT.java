package com.tutor.memory.local;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 PostgreSQL 验证 V66 user_facts：幂等键、软失效 fencing、加密双列与来源外键行为。 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "runIntegrationTests", matches = "true")
class UserFactsPostgresIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeAll
    void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void clean() {
        jdbc.update("TRUNCATE user_facts, episodes, conversations, users RESTART IDENTITY CASCADE");
    }

    @Test
    void migratesFactTableWithIndexesAndConstraints() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name='user_facts'
                  AND column_name IN ('fact_text', 'fact_encrypted', 'fact_encryption_key_id',
                                      'fact_hash', 'category', 'confidence', 'status', 'superseded_by',
                                      'memory_generation', 'source_episode_id', 'expires_at')
                """, Integer.class)).isEqualTo(11);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE tablename='user_facts'
                  AND indexname IN ('uq_user_facts_hash', 'idx_user_facts_active', 'idx_user_facts_source')
                """, Integer.class)).isEqualTo(3);
    }

    @Test
    void insertIsIdempotentOnCanonicalHashAndAllowsDistinctFacts() {
        long userId = insertUser();

        FactStore store = new FactStore(jdbc);
        long first = store.insertIfAbsentReturningId(userId, null, 0L, "用户目标：秋招后端岗！", "goal", 0.8);
        long duplicate = store.insertIfAbsentReturningId(userId, null, 0L, "用户目标秋招后端岗", "goal", 0.8);
        long distinct = store.insertIfAbsentReturningId(userId, null, 0L, "用户目标：转算法岗", "goal", 0.7);

        assertThat(first).isPositive();
        assertThat(duplicate).isZero();
        assertThat(distinct).isPositive().isNotEqualTo(first);
        assertThat(store.activeByUser(userId, 50)).hasSize(2);
    }

    @Test
    void markSupersededFencesOnMemoryGeneration() {
        long userId = insertUser();
        FactStore store = new FactStore(jdbc);
        long oldId = store.insertIfAbsentReturningId(userId, null, 0L, "用户在学习Java", "skill", 0.7);
        long newId = store.insertIfAbsentReturningId(userId, null, 0L, "用户主攻Spring后端", "skill", 0.8);

        assertThat(store.markSuperseded(userId, oldId, newId, 99L)).isFalse();
        assertThat(store.markSuperseded(userId, oldId, newId, 0L)).isTrue();

        assertThat(jdbc.queryForObject(
                "SELECT status FROM user_facts WHERE id=?", String.class, oldId)).isEqualTo("superseded");
        assertThat(jdbc.queryForObject(
                "SELECT superseded_by FROM user_facts WHERE id=?", Long.class, oldId)).isEqualTo(newId);
        // 已失效事实不再出现在有效列表，幂等键随之释放
        assertThat(store.activeByUser(userId, 50)).singleElement()
                .extracting(FactStore.UserFact::id).isEqualTo(newId);
        long reAdd = store.insertIfAbsentReturningId(userId, null, 0L, "用户在学习Java", "skill", 0.7);
        assertThat(reAdd).isPositive().isNotEqualTo(oldId);
    }

    @Test
    void deletingSourceEpisodeNullsProvenanceButKeepsFact() {
        long userId = insertUser();
        long conversationId = jdbc.queryForObject(
                "INSERT INTO conversations (user_id, last_active_at) VALUES (?, now()) RETURNING id",
                Long.class, userId);
        long episodeId = jdbc.queryForObject("""
                INSERT INTO episodes (user_id, conversation_id, summary) VALUES (?, ?, '测试摘要')
                RETURNING id
                """, Long.class, userId, conversationId);

        FactStore store = new FactStore(jdbc);
        long factId = store.insertIfAbsentReturningId(userId, episodeId, 0L, "用户偏好中文讲解", "preference", 0.8);
        jdbc.update("DELETE FROM episodes WHERE id=?", episodeId);

        assertThat(jdbc.queryForObject(
                "SELECT source_episode_id FROM user_facts WHERE id=?", Long.class, factId)).isNull();
        assertThat(store.activeByUser(userId, 50)).hasSize(1);
    }

    @Test
    void deleteBySourceEpisodeIdOnlyRemovesFactsFromThatSource() {
        long userId = insertUser();
        long conversationId = jdbc.queryForObject(
                "INSERT INTO conversations (user_id, last_active_at) VALUES (?, now()) RETURNING id",
                Long.class, userId);
        long episodeA = jdbc.queryForObject("""
                INSERT INTO episodes (user_id, conversation_id, summary) VALUES (?, ?, '摘要A')
                RETURNING id
                """, Long.class, userId, conversationId);
        long episodeB = jdbc.queryForObject("""
                INSERT INTO episodes (user_id, conversation_id, summary) VALUES (?, ?, '摘要B')
                RETURNING id
                """, Long.class, userId, conversationId);

        FactStore store = new FactStore(jdbc);
        store.insertIfAbsentReturningId(userId, episodeA, 0L, "事实A", "goal", 0.6);
        store.insertIfAbsentReturningId(userId, episodeB, 0L, "事实B", "skill", 0.6);

        int removed = store.deleteBySourceEpisodeId(userId, episodeA);

        assertThat(removed).isEqualTo(1);
        List<FactStore.UserFact> remaining = store.activeByUser(userId, 50);
        assertThat(remaining).singleElement().extracting(FactStore.UserFact::factText).isEqualTo("事实B");
    }

    @Test
    void encryptedRoundTripDecryptsWithKeyRotation() {
        long userId = insertUser();
        FactStore encrypted = new FactStore(jdbc, "new-key", "v2", "old-key", "v1");

        long id = encrypted.insertIfAbsentReturningId(userId, null, 0L, "用户约束：每天学习2小时", "constraint", 0.9);
        assertThat(id).isPositive();

        assertThat(encrypted.activeByUser(userId, 50)).singleElement()
                .extracting(FactStore.UserFact::factText).isEqualTo("用户约束：每天学习2小时");

        FactStore rotatedBack = new FactStore(jdbc, "old-key", "v1", "", "");
        // 密钥版本不匹配时按设计回退到 fact_text 脱敏兼容投影，而不是报错或丢数据。
        assertThat(rotatedBack.activeByUser(userId, 50)).singleElement()
                .extracting(FactStore.UserFact::factText).isEqualTo("用户约束：每天学习2小时");
    }

    private long insertUser() {
        return jdbc.queryForObject("INSERT INTO users DEFAULT VALUES RETURNING id", Long.class);
    }
}
