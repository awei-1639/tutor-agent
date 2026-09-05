package com.tutor.coaching.plan;

import com.tutor.contract.Purpose;
import com.tutor.llm.JsonGenerationGateway;
import com.tutor.llm.LlmMessage;
import com.tutor.llm.structured.StructuredOutputService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanServiceTest {
    private final PlanStore store = mock(PlanStore.class);
    private final PlanService service = new PlanService(store, mock(StructuredOutputService.class));

    @Test
    void replansOnlyWhenThereAreTasksAndCompletionFallsBelowThreshold() {
        when(store.progress(42L))
                .thenReturn(new PlanStore.PlanProgress(2, 5))
                .thenReturn(new PlanStore.PlanProgress(3, 5))
                .thenReturn(new PlanStore.PlanProgress(0, 0));

        assertThat(service.shouldReplan(42L)).isTrue();
        assertThat(service.shouldReplan(42L)).isFalse();
        assertThat(service.shouldReplan(42L)).isFalse();
    }

    @Test
    void createsAtMostThreeDistinctEvidenceTasksInEncounterOrder() {
        when(store.activePlanIdOrCreate(anyLong(), anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(9L);
        when(store.hasEvidenceTask(anyLong(), anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(false);
        when(store.addEvidenceTask(anyLong(), anyLong(), any(LocalDate.class), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> new PlanModels.PlanTask(
                        100L,
                        invocation.getArgument(0),
                        invocation.getArgument(2),
                        invocation.getArgument(4),
                        "practice",
                        45,
                        invocation.getArgument(5)));

        List<PlanModels.PlanTask> tasks = service.createEvidenceTasks(
                42L,
                "补齐后端能力",
                List.of("skill:java", "skill:sql", "skill:java", "skill:redis", "skill:kafka"));

        assertThat(tasks).hasSize(3);
        ArgumentCaptor<String> skillIds = ArgumentCaptor.forClass(String.class);
        verify(store, times(3)).addEvidenceTask(
                anyLong(), anyLong(), any(LocalDate.class), skillIds.capture(), anyString(), anyString());
        assertThat(skillIds.getAllValues())
                .containsExactly("skill:java", "skill:sql", "skill:redis");
    }

    @Test
    void preservesStructuredPlanPromptAndMapsSevenTasksToTheCurrentWeek() {
        JsonGenerationGateway gateway = mock(JsonGenerationGateway.class);
        String output = sevenDayPlanJson();
        when(gateway.chatJson(eq(Purpose.PLAN), any(), eq("trace-plan"), isNull(), eq(1)))
                .thenReturn(output);
        PlanService generatedPlans = new PlanService(store, new StructuredOutputService(gateway, null));
        when(store.saveGeneratedPlan(eq(42L), eq("后端学习计划"), any(LocalDate.class), any()))
                .thenAnswer(invocation -> new PlanModels.Plan(
                        91L,
                        42L,
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        ((LocalDate) invocation.getArgument(2)).plusDays(6),
                        "active"));

        PlanModels.Plan plan = generatedPlans.generateWeeklyPlan(
                42L, "转后端岗位", "Java, SQL", "完成了 Java 集合练习", "trace-plan");

        assertThat(plan).isNotNull();
        assertThat(plan.id()).isEqualTo(91L);
        ArgumentCaptor<List<LlmMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(gateway).chatJson(eq(Purpose.PLAN), messages.capture(), eq("trace-plan"), isNull(), eq(1));
        String request = messages.getValue().getLast().content();
        assertThat(request).contains("目标: 转后端岗位", "当前技能: Java, SQL", "近期打卡: 完成了 Java 集合练习");

        ArgumentCaptor<List<PlanModels.PlanTaskDraft>> tasks = ArgumentCaptor.forClass(List.class);
        verify(store).saveGeneratedPlan(eq(42L), eq("后端学习计划"), any(LocalDate.class), tasks.capture());
        List<PlanModels.PlanTaskDraft> drafts = tasks.getValue();
        assertThat(drafts).hasSize(7);
        assertThat(drafts).extracting(PlanModels.PlanTaskDraft::day)
                .containsExactly(plan.weekStart(), plan.weekStart().plusDays(1), plan.weekStart().plusDays(2),
                        plan.weekStart().plusDays(3), plan.weekStart().plusDays(4), plan.weekStart().plusDays(5),
                        plan.weekStart().plusDays(6));
        assertThat(drafts).extracting(PlanModels.PlanTaskDraft::kind)
                .containsExactly("learn", "practice", "review", "learn", "practice", "review", "learn");
    }

    private String sevenDayPlanJson() {
        return """
                {"goal_summary":"后端学习计划","days":[
                  {"day":"周一","content":"任务一","kind":"learn","related_skills":["skill:java"],"estimated_minutes":30},
                  {"day":"周二","content":"任务二","kind":"practice","related_skills":["skill:sql"],"estimated_minutes":45},
                  {"day":"周三","content":"任务三","kind":"review","related_skills":["skill:redis"],"estimated_minutes":60},
                  {"day":"周四","content":"任务四","kind":"learn","related_skills":["skill:java"],"estimated_minutes":30},
                  {"day":"周五","content":"任务五","kind":"practice","related_skills":["skill:sql"],"estimated_minutes":45},
                  {"day":"周六","content":"任务六","kind":"review","related_skills":["skill:redis"],"estimated_minutes":60},
                  {"day":"周日","content":"任务七","kind":"learn","related_skills":["skill:java"],"estimated_minutes":30}
                ]}
                """;
    }
}
