package com.tutor.chat.feedback;

import com.tutor.chat.feedback.MessageFeedbackService.Attribution;
import com.tutor.chat.feedback.MessageFeedbackService.Feedback;
import com.tutor.chat.feedback.MessageFeedbackService.ReasonCount;
import com.tutor.chat.feedback.MessageFeedbackService.TraceFeedback;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL regression coverage for the feedback SQL boundary: user-scoped upsert, aggregates,
 * and the negative-feedback attribution join over turn traces.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "runIntegrationTests", matches = "true")
class MessageFeedbackStorePostgresIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private MessageFeedbackStore store;

    @BeforeAll
    void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new MessageFeedbackStore(jdbc);
    }

    @BeforeEach
    void clean() {
        jdbc.update("TRUNCATE message_feedback, turn_traces, messages, conversations, users RESTART IDENTITY CASCADE");
    }

    @Test
    void savesFeedbackWithUserScopingAndUpsertSemantics() {
        long owner = insertUser("owner@example.com");
        long other = insertUser("other@example.com");
        long conversationId = insertConversation(owner);
        long messageId = insertMessage(conversationId, "assistant", "trace-save-1", "not_applicable");

        Feedback saved = store.save(owner, messageId, "helpful", null);
        assertThat(saved).isNotNull();
        assertThat(saved.id()).isPositive();
        assertThat(saved.messageId()).isEqualTo(messageId);
        assertThat(saved.rating()).isEqualTo("helpful");
        assertThat(saved.reason()).isNull();
        assertThat(saved.traceId()).isEqualTo("trace-save-1");

        Feedback updated = store.save(owner, messageId, "not_helpful", "答非所问");
        assertThat(updated.id()).isEqualTo(saved.id());
        assertThat(updated.rating()).isEqualTo("not_helpful");
        assertThat(store.totals()).containsExactly(1L, 0L, 1L);

        assertThat(store.save(other, messageId, "helpful", null)).isNull();
        long userMessageId = insertMessage(conversationId, "user", "trace-save-2", "not_applicable");
        assertThat(store.save(owner, userMessageId, "helpful", null)).isNull();

        List<ReasonCount> reasons = store.reasons();
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0).reason()).isEqualTo("答非所问");
        assertThat(reasons.get(0).count()).isEqualTo(1L);

        List<TraceFeedback> latest = store.latestNotHelpful();
        assertThat(latest).hasSize(1);
        assertThat(latest.get(0).traceId()).isEqualTo("trace-save-1");
        assertThat(latest.get(0).messageId()).isEqualTo(messageId);
        assertThat(latest.get(0).reason()).isEqualTo("答非所问");
        assertThat(latest.get(0).createdAt()).isNotNull();
    }

    @Test
    void aggregatesReasonsAndJoinsAttributionOverTurnTraces() {
        long user = insertUser("attrib@example.com");
        long conversationId = insertConversation(user);
        long traced = insertMessage(conversationId, "assistant", "trace-att-1", "unsupported");
        long untraced = insertMessage(conversationId, "assistant", "trace-att-2", "not_applicable");
        long helpful = insertMessage(conversationId, "assistant", "trace-att-3", "not_applicable");
        long repeat = insertMessage(conversationId, "assistant", "trace-att-4", "not_applicable");

        store.save(user, traced, "not_helpful", "检索不准");
        store.save(user, untraced, "not_helpful", null);
        store.save(user, repeat, "not_helpful", "检索不准");
        store.save(user, helpful, "helpful", null);

        insertTrace("trace-att-1", "router", "{\"retrieval_facets\":\"[\\\"skill:java\\\"]\"}");
        insertTrace("trace-att-1", "retrieve", """
                {"requested_mode":"hybrid","hops":2,"retrieval_profile_version":"prof-v2",
                 "final_graph_evidence_count":3,"final_direct_evidence_count":5,
                 "dense_candidate_count":10,"sparse_candidate_count":8,
                 "graph_candidate_count":4,"graph_expansion_source_count":2,
                 "embedding_degraded":false,"sparse_degraded":false,
                 "rerank_applied":true,"rerank_degraded":false}""");

        assertThat(store.totals()).containsExactly(4L, 1L, 3L);

        List<ReasonCount> reasons = store.reasons();
        assertThat(reasons).hasSize(2);
        assertThat(reasons.get(0).reason()).isEqualTo("检索不准");
        assertThat(reasons.get(0).count()).isEqualTo(2L);
        assertThat(reasons.get(1).reason()).isEqualTo("unspecified");
        assertThat(reasons.get(1).count()).isEqualTo(1L);

        List<TraceFeedback> latest = store.latestNotHelpful();
        assertThat(latest).extracting(TraceFeedback::traceId)
                .containsExactlyInAnyOrder("trace-att-1", "trace-att-2", "trace-att-4");

        List<Attribution> attributions = store.attributions();
        assertThat(attributions).hasSize(3);

        Attribution withTrace = attributions.stream()
                .filter(a -> "hybrid".equals(a.requestedMode())).findFirst().orElseThrow();
        assertThat(withTrace.retrievalFacets()).isEqualTo("[\"skill:java\"]");
        assertThat(withTrace.hops()).isEqualTo(2);
        assertThat(withTrace.retrievalProfileVersion()).isEqualTo("prof-v2");
        assertThat(withTrace.finalGraphEvidenceCount()).isEqualTo(3L);
        assertThat(withTrace.finalDirectEvidenceCount()).isEqualTo(5L);
        assertThat(withTrace.denseCandidateCount()).isEqualTo(10L);
        assertThat(withTrace.sparseCandidateCount()).isEqualTo(8L);
        assertThat(withTrace.graphCandidateCount()).isEqualTo(4L);
        assertThat(withTrace.graphExpansionSourceCount()).isEqualTo(2L);
        assertThat(withTrace.embeddingDegraded()).isFalse();
        assertThat(withTrace.sparseDegraded()).isFalse();
        assertThat(withTrace.rerankApplied()).isTrue();
        assertThat(withTrace.rerankDegraded()).isFalse();
        assertThat(withTrace.citationStatus()).isEqualTo("unsupported");
        assertThat(withTrace.reason()).isEqualTo("检索不准");
        assertThat(withTrace.count()).isEqualTo(1L);

        Attribution withoutTrace = attributions.stream()
                .filter(a -> "unavailable".equals(a.requestedMode()))
                .filter(a -> "unspecified".equals(a.reason())).findFirst().orElseThrow();
        assertThat(withoutTrace.retrievalFacets()).isEqualTo("[]");
        assertThat(withoutTrace.hops()).isZero();
        assertThat(withoutTrace.retrievalProfileVersion()).isEqualTo("unavailable");
        assertThat(withoutTrace.denseCandidateCount()).isZero();
        assertThat(withoutTrace.citationStatus()).isEqualTo("not_applicable");
    }

    private long insertUser(String email) {
        return jdbc.queryForObject(
                "INSERT INTO users(email, password_hash, name) VALUES (?, 'hash', ?) RETURNING id",
                Long.class, email, "Feedback User");
    }

    private long insertConversation(long userId) {
        return jdbc.queryForObject(
                "INSERT INTO conversations(user_id) VALUES (?) RETURNING id", Long.class, userId);
    }

    private long insertMessage(long conversationId, String role, String traceId, String citationStatus) {
        return jdbc.queryForObject("""
                INSERT INTO messages(conversation_id, role, content, trace_id, citation_status)
                VALUES (?, ?, '测试消息内容', ?, ?) RETURNING id""",
                Long.class, conversationId, role, traceId, citationStatus);
    }

    private void insertTrace(String traceId, String node, String snapshot) {
        jdbc.update("INSERT INTO turn_traces(trace_id, node, snapshot) VALUES (?, ?, ?::jsonb)",
                traceId, node, snapshot);
    }
}
