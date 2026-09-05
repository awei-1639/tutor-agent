package com.tutor.coaching.push;

import com.tutor.coaching.plan.PlanModels;
import com.tutor.coaching.plan.PlanService;
import com.tutor.identity.profile.ProfileService;
import com.tutor.identity.profile.SkillAlignService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CareerGapServiceTest {
    @Mock
    CareerJobStore jobs;
    @Mock
    ProfileService profiles;
    @Mock
    SkillAlignService alignments;
    @Mock
    PlanService plans;

    @Test
    void onlyAddsSkillsThatTheReleasedJobActuallyRequires() {
        CareerJobStore.Job job = new CareerJobStore.Job(
                10L, "后端工程师", "示例公司", "杭州", List.of("skill:java"));
        PlanModels.PlanTask task = new PlanModels.PlanTask(
                1L, 2L, LocalDate.now(), "完成 Java 练习", "practice", 30, "提交答案");
        when(jobs.findReleasedById(10L)).thenReturn(job);
        when(plans.createEvidenceTasks(7L, "补齐「后端工程师」所需能力", List.of("skill:java")))
                .thenReturn(List.of(task));

        assertThat(new CareerGapService(jobs, profiles, alignments, plans)
                .addGapTasks(7L, 10L, List.of("skill:java", "skill:python")))
                .containsExactly(task);
        verify(jobs).findReleasedById(10L);
        verify(plans).createEvidenceTasks(7L, "补齐「后端工程师」所需能力", List.of("skill:java"));
    }

    @Test
    void rejectsASelectionThatIsNotAJobRequirement() {
        when(jobs.findReleasedById(10L)).thenReturn(new CareerJobStore.Job(
                10L, "后端工程师", "示例公司", "杭州", List.of("skill:java")));

        assertThatThrownBy(() -> new CareerGapService(jobs, profiles, alignments, plans)
                .addGapTasks(7L, 10L, List.of("skill:python")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("请选择该岗位的待补齐技能");
    }

    @Test
    void computesCardsFromProfileAlignmentAndReleasedJobs() {
        when(profiles.snapshot(7L)).thenReturn(Map.of(
                "skills", List.of(Map.of("name", "Java")),
                "target_position", Map.of("value", "后端")));
        when(alignments.align(List.of("Java"))).thenReturn(Map.of("Java", "skill:java"));
        when(jobs.findReleasedForTarget("后端")).thenReturn(List.of(new CareerJobStore.Job(
                10L, "后端工程师", "示例公司", "杭州", List.of("skill:java", "skill:sql"))));
        when(alignments.speedupables(eq(List.of("skill:java")), eq(List.of("skill:sql"))))
                .thenReturn(Set.of());

        List<CareerGapService.GapCard> cards = new CareerGapService(jobs, profiles, alignments, plans)
                .topGaps(7L);

        assertThat(cards).hasSize(1);
        assertThat(cards.getFirst().matched()).containsExactly("skill:java");
        assertThat(cards.getFirst().missing()).containsExactly("skill:sql");
        verify(jobs).findReleasedForTarget("后端");
    }
}
