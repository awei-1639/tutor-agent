package com.tutor.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Neo4j 健康探针行为配置（仅影响 /actuator/health，不影响 /readyz）。 */
@ConfigurationProperties(prefix = "neo4j.health")
public record Neo4jHealthProperties(long startupGraceSeconds) {
    public Neo4jHealthProperties {
        if (startupGraceSeconds < 0) {
            throw new IllegalArgumentException("neo4j health startup grace must be >= 0");
        }
    }
}
