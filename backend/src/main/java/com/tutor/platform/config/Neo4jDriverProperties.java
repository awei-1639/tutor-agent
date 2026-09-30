package com.tutor.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Neo4j 驱动连接层超时配置，与查询/熔断超时 ({@link Neo4jProperties}) 彻底区分。 */
@ConfigurationProperties(prefix = "neo4j.driver")
public record Neo4jDriverProperties(
        long connectionTimeoutSeconds,
        long acquisitionTimeoutSeconds,
        long livenessCheckSeconds
) {
    public Neo4jDriverProperties {
        if (connectionTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("neo4j connection timeout must be positive");
        }
        // 获取一条连接可能需先新建(最多耗时 connectionTimeout)，故获取超时必须 >= 连接超时，
        // 否则冷启动/池空时获取会先于建连超时。驱动默认亦是此关系(连接 30s / 获取 60s)。
        if (acquisitionTimeoutSeconds < connectionTimeoutSeconds) {
            throw new IllegalArgumentException(
                    "neo4j acquisition timeout must be >= connection timeout");
        }
        if (livenessCheckSeconds <= 0) {
            throw new IllegalArgumentException("neo4j liveness check timeout must be positive");
        }
    }
}
