package com.tutor.agent.tool;

import com.tutor.contract.SideEffect;
import com.tutor.contract.ToolSpec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 工具目录渲染：模型靠它选择工具，必须只含该 agent 可用的工具并带参数说明。 */
class ToolRegistryCatalogTest {

    private ToolRegistry registryWithTools() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ToolRegistration(
                new ToolSpec("plan_today", ToolInputs.Empty.class, Duration.ofSeconds(3), true, SideEffect.L0,
                        "读取用户今天的学习计划任务，无参数"),
                Set.of("chat"), (input, context) -> List.of()));
        registry.register(new ToolRegistration(
                new ToolSpec("resume_upload", ToolInputs.ResumeUpload.class, Duration.ofSeconds(120), false, SideEffect.L1,
                        "上传并解析简历文件，参数 file（文件）"),
                Set.of("resume"), (input, context) -> java.util.Map.of()));
        registry.register(new ToolRegistration(
                new ToolSpec("push_run", ToolInputs.Empty.class, Duration.ofSeconds(30), false, SideEffect.L2,
                        "立即执行一次岗位匹配推送，无参数"),
                Set.of("scheduler", "chat"), (input, context) -> java.util.Map.of()));
        return registry;
    }

    @Test
    void catalogOnlyListsToolsAllowedForAgent() {
        String catalog = registryWithTools().catalogFor("chat");

        assertThat(catalog).contains("plan_today").contains("读取用户今天的学习计划任务");
        assertThat(catalog).contains("push_run");
        assertThat(catalog).doesNotContain("resume_upload");
        assertThat(catalog).contains("副作用 L0").contains("副作用 L2");
    }

    @Test
    void catalogRendersParameterNamesFromRecordComponents() {
        String catalog = registryWithTools().catalogFor("resume");

        assertThat(catalog).contains("resume_upload").contains("file");
        assertThat(catalog).doesNotContain("plan_today");
    }

    @Test
    void catalogForUnknownAgentIsEmptyPlaceholder() {
        assertThat(registryWithTools().catalogFor("nobody")).isEqualTo("（无）");
    }
}
