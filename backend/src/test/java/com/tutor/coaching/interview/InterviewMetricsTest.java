package com.tutor.coaching.interview;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewMetricsTest {
    @Test
    void recordsLowCardinalityRequestAndDurationMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InterviewMetrics metrics = new InterviewMetrics(registry);

        var timer = metrics.startTimer();
        metrics.request("answer", "completed");
        metrics.stop(timer, "answer");

        assertThat(registry.get("tutor.interview.requests").tag("operation", "answer").tag("result", "completed")
                .counter().count()).isEqualTo(1D);
        assertThat(registry.get("tutor.interview.duration").tag("operation", "answer").timer().count()).isEqualTo(1L);
    }
}
