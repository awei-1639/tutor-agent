package com.tutor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Neo4j 查询超时与故障熔断配置。 */
@ConfigurationProperties(prefix = "neo4j.resilience")
public record Neo4jProperties(
        long queryTimeoutSeconds,
        int failureThreshold,
        long openSeconds
) {
    public Neo4jProperties {
        if (queryTimeoutSeconds <= 0) throw new IllegalArgumentException("neo4j query timeout must be positive");
        if (failureThreshold <= 0) throw new IllegalArgumentException("neo4j failure threshold must be positive");
        if (openSeconds <= 0) throw new IllegalArgumentException("neo4j open seconds must be positive");
    }

    public static Neo4jProperties defaults() {
        return new Neo4jProperties(2, 3, 30);
    }
}
