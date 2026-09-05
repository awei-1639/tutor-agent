package com.tutor.identity.auth;

import com.tutor.llm.LlmGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 注册 → JWT → 受保护 REST 端点的真实 HTTP 链路，不调用外部 LLM/Neo4j。 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false",
        "tutor.jwt.secret=test_only_jwt_secret_at_least_32_characters_long"
})
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "runIntegrationTests", matches = "true")
class AuthHttpPostgresIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mvc;

    @MockitoBean LlmGateway llmGateway;
    @MockitoBean Driver neo4jDriver;

    @Test
    void registerThenAccessProtectedEndpointsWithIssuedJwt() throws Exception {
        var registration = mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"http-user@example.com\",\"password\":\"correct-horse\",\"name\":\"HTTP User\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        String token = registration.getCookie("tutor_access").getValue();
        assertThat(token).isNotBlank();

        mvc.perform(get("/profile"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/profile").cookie(registration.getCookie("tutor_access")))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"));

        mvc.perform(get("/conversations").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }
}
