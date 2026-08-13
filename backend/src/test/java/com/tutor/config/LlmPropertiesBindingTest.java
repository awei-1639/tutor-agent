package com.tutor.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
