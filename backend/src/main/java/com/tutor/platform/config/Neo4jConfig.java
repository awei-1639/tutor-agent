package com.tutor.platform.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Logging;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Neo4j 驱动装配。连接/连接池/存活探测/重试/指标集中在此;
 * 查询事务超时+熔断参数见 {@link Neo4jProperties}, 连接层超时见 {@link Neo4jDriverProperties}。
 */
@Configuration
@EnableConfigurationProperties({Neo4jProperties.class, Neo4jDriverProperties.class})
public class Neo4jConfig {

    @Bean(destroyMethod = "close")
    public Driver neo4jDriver(
            @Value("${neo4j.uri}") String uri,
            @Value("${neo4j.username}") String user,
            @Value("${neo4j.password}") String password,
            Neo4jDriverProperties driverProperties) {
        Config config = Config.builder()
                .withConnectionTimeout(driverProperties.connectionTimeoutSeconds(), TimeUnit.SECONDS)
                .withConnectionAcquisitionTimeout(
                        driverProperties.acquisitionTimeoutSeconds(), TimeUnit.SECONDS)
                // 借出前对空闲超过该阈值的连接探活, 避免 Neo4j 重启后复用陈旧连接抛
                // ServiceUnavailable/SessionExpired 被 Neo4jResilience 误计为失败而假开路。
                .withConnectionLivenessCheckTimeout(
                        driverProperties.livenessCheckSeconds(), TimeUnit.SECONDS)
                // 把"只熔断不重试"落到驱动层: 禁用托管事务自动重试(默认 30s), 防止将来改用
                // executeRead/Write 时 30s 重试窗口静默盖过查询超时与熔断意图。当前全为自动
                // 提交事务(GraphStore/SkillAlignService), 此项即便惰性也无害。
                .withMaxTransactionRetryTime(0, TimeUnit.MILLISECONDS)
                // 打开连接池指标(经 Neo4jDriverMetrics 采集到 Micrometer),
                // 使冷启动击穿 / 获取超时 / 真实宕机 可区分。
                .withDriverMetrics()
                // 驱动内部日志显式走 SLF4J, 不依赖 JUL→SLF4J 桥。
                .withLogging(Logging.slf4j())
                .build();
        return GraphDatabase.driver(uri, AuthTokens.basic(user, password), config);
    }
}
