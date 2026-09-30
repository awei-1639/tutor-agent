package com.tutor.platform.config;

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
        // 镜像 application.yml 基线默认值 (query-timeout 5s); 完整取值理由见那里的注释。
        // 更早的 2s 在冷启动/内存紧张下被轻易击穿，而熔断层只熔断不重试，击穿即静默空结果。
        return new Neo4jProperties(5, 3, 30);
    }
}
