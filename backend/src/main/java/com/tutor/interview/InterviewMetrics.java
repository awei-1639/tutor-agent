package com.tutor.interview;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/** Low-cardinality operational metrics for the interview lifecycle. */
@Component
public class InterviewMetrics {
    private final MeterRegistry registry;

    public InterviewMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void request(String operation, String result) {
        Counter.builder("tutor.interview.requests")
                .description("Interview API requests")
                .tag("operation", operation)
                .tag("result", result)
                .register(registry)
                .increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void stop(Timer.Sample sample, String operation) {
        sample.stop(Timer.builder("tutor.interview.duration")
                .description("Interview API duration")
                .tag("operation", operation)
                .publishPercentiles(0.5, 0.95)
                .register(registry));
    }
}
