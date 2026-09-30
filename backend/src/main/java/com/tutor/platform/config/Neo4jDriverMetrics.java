package com.tutor.platform.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.neo4j.driver.ConnectionPoolMetrics;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Component;

import java.util.function.ToLongFunction;

/** 将 Neo4j 连接池指标暴露到 Micrometer, 照亮冷启动击穿 / 获取超时 / 宕机的区别。 */
@Component
class Neo4jDriverMetrics {

    Neo4jDriverMetrics(Driver driver, MeterRegistry registry) {
        register(driver, registry, "neo4j.pool.acquisition.timeouts", ConnectionPoolMetrics::timedOutToAcquire);
        register(driver, registry, "neo4j.pool.connections.in_use", ConnectionPoolMetrics::inUse);
        register(driver, registry, "neo4j.pool.connections.idle", ConnectionPoolMetrics::idle);
        register(driver, registry, "neo4j.pool.acquiring", ConnectionPoolMetrics::acquiring);
    }

    private static void register(Driver driver, MeterRegistry registry, String name,
                                 ToLongFunction<ConnectionPoolMetrics> metric) {
        // 指标在 scrape 时惰性求值; 池创建前 connectionPoolMetrics() 为空, 求和得 0。
        registry.gauge(name, driver,
                d -> d.metrics().connectionPoolMetrics().stream().mapToLong(metric).sum());
    }
}
