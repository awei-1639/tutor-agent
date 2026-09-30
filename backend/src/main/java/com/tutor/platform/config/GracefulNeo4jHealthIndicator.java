package com.tutor.platform.config;

import org.neo4j.driver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * /actuator/health 的 neo4j 指示器，带启动宽限。
 *
 * <p>背景：后端常先于 Neo4j 容器就绪启动（Bolt 握手在服务端未监听时被直接断开），
 * 自动配置的 Neo4jHealthIndicator 在此窗口会把整体状态打成 DOWN 并触发告警，
 * Neo4j 就绪后又自动恢复——纯启动噪音。宽限期内失败报 UNKNOWN（默认状态聚合器
 * 不因 UNKNOWN 拉低整体状态），窗口外如实报 DOWN，真实宕机不被掩盖。
 *
 * <p>/readyz（{@link HealthReadinessService}）不做宽限：编排探针必须如实反映
 * 依赖可用性，否则会在依赖未就绪时放行流量。
 */
@Component("neo4jHealthIndicator")
public class GracefulNeo4jHealthIndicator implements HealthIndicator {
    private static final Logger log = LoggerFactory.getLogger(GracefulNeo4jHealthIndicator.class);

    private final Driver driver;
    private final long graceMillis;
    private final long startedAtMillis = System.currentTimeMillis();

    public GracefulNeo4jHealthIndicator(Driver driver, Neo4jHealthProperties properties) {
        this.driver = driver;
        this.graceMillis = properties.startupGraceSeconds() * 1000;
    }

    @Override
    public Health health() {
        try {
            driver.verifyConnectivity();
            return Health.up().build();
        } catch (RuntimeException error) {
            if (nowMillis() - startedAtMillis < graceMillis) {
                log.warn("Neo4j 尚未就绪（启动宽限内，状态临时 UNKNOWN）: {}", error.getMessage());
                return Health.unknown().withDetail("neo4j", "starting").build();
            }
            return Health.down(error).build();
        }
    }

    /** 时钟钩子，仅供测试替换（构造时刻视为应用启动时刻）。 */
    long nowMillis() {
        return System.currentTimeMillis();
    }
}
