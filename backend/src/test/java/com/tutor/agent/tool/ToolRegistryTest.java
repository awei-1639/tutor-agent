package com.tutor.agent.tool;

import com.tutor.contract.SideEffect;
import com.tutor.contract.ToolSpec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {
    @Test
    void rejectsDuplicateAndUnknownTools() {
        ToolRegistry registry = new ToolRegistry();
        ToolRegistration registration = new ToolRegistration(
                new ToolSpec("demo", ToolInputs.Empty.class, Duration.ofSeconds(1), true, SideEffect.L0),
                Set.of("chat"),
                (input, context) -> null);

        registry.register(registration);
        assertThatThrownBy(() -> registry.register(registration))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("工具重复注册: demo");
        assertThatThrownBy(() -> registry.require("missing"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessage("未知工具: missing");
    }
}
