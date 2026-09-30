package com.tutor.platform.config;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

class GracefulNeo4jHealthIndicatorTest {
    private final Driver neo4j = mock(Driver.class);

    @Test
    void reportsUpWhenConnectivityVerified() {
        GracefulNeo4jHealthIndicator indicator = new GracefulNeo4jHealthIndicator(
                neo4j, new Neo4jHealthProperties(0));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void reportsUnknownWithinStartupGraceInsteadOfDown() {
        // 构造(视为应用启动)后约 30s 仍连不上, 宽限 120s → UNKNOWN(不拉低整体状态)。
        GracefulNeo4jHealthIndicator indicator = clockAt(30_000);
        doThrow(new IllegalStateException("bolt handshake terminated")).when(neo4j).verifyConnectivity();

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("neo4j", "starting");
    }

    @Test
    void reportsDownAfterStartupGraceExpires() {
        // 构造后约 121s 仍连不上, 宽限 120s 已过 → 如实 DOWN, 真实宕机不被宽限掩盖。
        GracefulNeo4jHealthIndicator indicator = clockAt(121_000);
        doThrow(new IllegalStateException("connection refused")).when(neo4j).verifyConnectivity();

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void startupGraceSecondsMustNotBeNegative() {
        assertThatThrownBy(() -> new Neo4jHealthProperties(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 时钟桩: 返回构造时刻 + elapsedMillis, 模拟应用已运行时长。 */
    private GracefulNeo4jHealthIndicator clockAt(long elapsedMillis) {
        GracefulNeo4jHealthIndicator spy = spy(new GracefulNeo4jHealthIndicator(
                neo4j, new Neo4jHealthProperties(120)));
        doReturn(System.currentTimeMillis() + elapsedMillis).when(spy).nowMillis();
        return spy;
    }
}
