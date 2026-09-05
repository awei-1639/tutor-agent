package com.tutor.knowledge.retrieval.resilience;

import com.tutor.knowledge.retrieval.resilience.Neo4jResilience;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class Neo4jResilienceTest {
    @Test
    void opensAfterRepeatedFailuresAndAllowsHalfOpenProbe() throws InterruptedException {
        Neo4jResilience resilience = new Neo4jResilience(2, Duration.ofMillis(40));
        AtomicInteger calls = new AtomicInteger();

        assertThat(resilience.execute("test", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("down");
        }).available()).isFalse();
        assertThat(resilience.execute("test", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("down");
        }).available()).isFalse();
        assertThat(resilience.execute("test", () -> {
            calls.incrementAndGet();
            return "should-not-run";
        }).available()).isFalse();
        assertThat(calls).hasValue(2);

        Thread.sleep(70);
        Neo4jResilience.QueryResult<String> recovered = resilience.execute("test", () -> "ok");
        assertThat(recovered.available()).isTrue();
        assertThat(recovered.value()).isEqualTo("ok");
    }
}
