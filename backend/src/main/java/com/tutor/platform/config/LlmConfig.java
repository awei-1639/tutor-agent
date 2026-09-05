package com.tutor.platform.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties({LlmProperties.class, Mem0Properties.class, Neo4jProperties.class, OssProperties.class,
        ClamAvProperties.class, AliyunOcrProperties.class, KnowledgeUploadProperties.class,
        KnowledgeIngestionProperties.class})
public class LlmConfig {

    @Bean(destroyMethod = "close")
    public Driver neo4jDriver(
            @Value("${neo4j.uri}") String uri,
            @Value("${neo4j.username}") String user,
            @Value("${neo4j.password}") String password,
            Neo4jProperties properties) {
        Config config = Config.builder()
                .withConnectionTimeout(properties.queryTimeoutSeconds(), TimeUnit.SECONDS)
                .withConnectionAcquisitionTimeout(properties.queryTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
        return GraphDatabase.driver(uri, AuthTokens.basic(user, password), config);
    }
}
