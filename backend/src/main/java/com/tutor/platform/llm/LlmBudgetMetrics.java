package com.tutor.platform.llm;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** 预算拒绝指标：按层记录 BudgetExhausted，供告警与容量分析。 */
@Component
public class LlmBudgetMetrics {

    private final MeterRegistry registry;

    public LlmBudgetMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void countRejected(BudgetExhausted.Kind kind) {
        registry.counter("tutor.llm.budget.rejected", "kind", kind.name().toLowerCase()).increment();
    }
}
