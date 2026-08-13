package com.tutor.auth;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.tutor.memory.local.ConversationStore;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 PostgreSQL + Flyway 验证；仅在 -DrunIntegrationTests=true 时执行。
 * 采用 pgvector 镜像，确保 V1 的 vector/pgcrypto 扩展也能被实际创建。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "runIntegrationTests", matches = "true")
class AuthServicePostgresIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private AuthService auth;
    private JwtService jwt;

    @BeforeAll
    void migrateAndCreateService() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jwt = new JwtService("test_only_jwt_secret_at_least_32_characters_long");
        auth = new AuthService(jdbc, jwt);
    }

    @Test
    void registersAndLogsInAgainstMigratedSchema() {
        AuthService.AuthResult registered = auth.register("  USER@Example.COM ", "correct-horse", " Ada ");

        assertThat(registered.userId()).isPositive();
        assertThat(jwt.parse(registered.token())).isEqualTo(registered.userId());
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, registered.userId()))
                .isEqualTo("user@example.com");
        assertThat(jdbc.queryForObject("SELECT name FROM users WHERE id=?", String.class, registered.userId()))
                .isEqualTo("Ada");

        AuthService.AuthResult loggedIn = auth.login("user@example.com", "correct-horse");
        assertThat(loggedIn.userId()).isEqualTo(registered.userId());
        assertThat(jwt.parse(loggedIn.token())).isEqualTo(registered.userId());
    }

    @Test
    void enforcesCaseInsensitiveUniqueEmailAndRejectsWrongPassword() {
        auth.register("exists@example.com", "correct-horse", "Existing");

        assertThatThrownBy(() -> auth.register("EXISTS@example.com", "another-password", "Duplicate"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("邮箱已注册");
        assertThatThrownBy(() -> auth.login("exists@example.com", "wrong-password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("邮箱或密码错误");
    }

    @Test
    void rotatesAndRevokesRefreshTokens() {
        AuthService.AuthResult first = auth.register("refresh@example.com", "correct-horse", "Refresh");
        AuthService.AuthResult rotated = auth.refresh(first.refreshToken());

        assertThat(rotated.userId()).isEqualTo(first.userId());
        assertThat(rotated.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThatThrownBy(() -> auth.refresh(first.refreshToken()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无效或已过期");

        auth.revokeRefreshToken(rotated.refreshToken());
        assertThatThrownBy(() -> auth.refresh(rotated.refreshToken()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isolatesConversationsByOwnerAtTheStoreBoundary() {
        long alice = auth.register("alice@example.com", "correct-horse", "Alice").userId();
        long bob = auth.register("bob@example.com", "correct-horse", "Bob").userId();
        ConversationStore store = new ConversationStore(jdbc);
        long aliceConversation = store.ensureConversation(null, alice);
        store.appendMessage(aliceConversation, "user", "Alice private message", null, null, 3);

        assertThat(store.belongsToUser(aliceConversation, alice)).isTrue();
        assertThat(store.belongsToUser(aliceConversation, bob)).isFalse();
        assertThat(store.recentMessagesForUser(aliceConversation, bob, 10)).isEmpty();
        assertThatThrownBy(() -> store.ensureConversation(aliceConversation, bob))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("会话不存在或无访问权限");
    }
}
