package com.tutor.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LlmPropertiesBindingTest {

    @Test
    void bindsTheFullNestedTimeoutConstructor() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "llm.timeout.router-seconds", "10",
                "llm.timeout.chat-seconds", "60",
                "llm.timeout.summary-seconds", "120",
                "llm.timeout.expert-seconds", "25")));

        LlmProperties properties = binder.bind("llm", Bindable.of(LlmProperties.class))
                .orElseThrow(() -> new AssertionError("llm properties were not bound"));

        assertEquals(10, properties.timeout().routerSeconds());
        assertEquals(60, properties.timeout().chatSeconds());
        assertEquals(120, properties.timeout().summarySeconds());
        assertEquals(25, properties.timeout().expertSeconds());
    }

    /**
     * Budget 有一个 4 参规范构造器和一个 2 参便捷构造器；缺 @ConstructorBinding 时绑定会
     * 静默产出 null，直到第一次 LLM 调用在 LlmBudgetGuard.reserveTurn 抛 NPE。
     */
    @Test
    void bindsTheFullNestedBudgetConstructorDespiteTheConvenienceOverload() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "llm.budget.daily-token-limit", "2000000",
                "llm.budget.turn-token-limit", "50000",
                "llm.budget.user-daily-token-limit", "300000",
                "llm.budget.background-share-percent", "20")));

        LlmProperties properties = binder.bind("llm", Bindable.of(LlmProperties.class))
                .orElseThrow(() -> new AssertionError("llm properties were not bound"));

        assertNotNull(properties.budget(), "llm.budget must bind, otherwise every LLM call NPEs");
        assertEquals(2_000_000L, properties.budget().dailyTokenLimit());
        assertEquals(50_000L, properties.budget().turnTokenLimit());
        assertEquals(300_000L, properties.budget().userDailyTokenLimit());
        assertEquals(20, properties.budget().backgroundSharePercent());
    }
}
