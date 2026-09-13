package com.tutor.coaching.plan;

import com.tutor.conversation.memory.local.FactStore;
import com.tutor.coaching.interview.InterviewSkillEvidenceStore;
import com.tutor.identity.profile.ProfileMerger;
import com.tutor.identity.profile.ProfileService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanContextServiceTest {
    private final ProfileService profiles = mock(ProfileService.class);
    private final FactStore facts = mock(FactStore.class);
    private final InterviewSkillEvidenceStore evidence = mock(InterviewSkillEvidenceStore.class);
    private final PlanContextService service = new PlanContextService(profiles, facts, evidence);

    @Test
    void assemblesProfileFactsAndVerifiedWeaknesses() {
        when(profiles.snapshot(7L)).thenReturn(Map.of(
                "target_position", Map.of("value", "后端开发", "confidence", 0.9),
                "daily_hours", Map.of("value", "2", "confidence", 0.8),
                "preferred_format", List.of("视频", "实战项目"),
                "skills", List.of(
                        Map.of("name", "skill:Java", "confidence", 1.0),
                        Map.of("name", "RAG", "confidence", 0.72))));
        when(facts.activeByUser(7L, 8)).thenReturn(List.of(
                new FactStore.UserFact(1L, "六个月内转后端", "goal", 0.9, Instant.now(), Instant.now())));
        when(evidence.recentWeakSkills(7L, ProfileMerger.INTERVIEW_MIN_SCORE, 5))
                .thenReturn(List.of("skill:缓存", "Redis"));

        PlanContextService.LearnerContext context = service.load(7L);

        assertThat(context.profileSummary())
                .contains("目标岗位: 后端开发")
                .contains("每日可学: 2")
                .contains("偏好形式: 视频/实战项目")
                .contains("Java(1.00)")
                .contains("RAG(0.72)");
        assertThat(context.facts()).containsExactly("六个月内转后端");
        assertThat(context.weakSkills()).containsExactly("缓存", "Redis"); // skill: 前缀被剥离
        assertThat(context.toPromptBlock()).contains("用户画像与历史证据", "面试验证的薄弱项");
    }

    @Test
    void emptyContextProducesNoPromptBlock() {
        when(profiles.snapshot(7L)).thenReturn(Map.of());
        when(facts.activeByUser(7L, 8)).thenReturn(List.of());
        when(evidence.recentWeakSkills(anyLong(), anyDouble(), anyInt())).thenReturn(List.of());

        PlanContextService.LearnerContext context = service.load(7L);

        assertThat(context.isEmpty()).isTrue();
        assertThat(context.toPromptBlock()).isEmpty();
    }

    @Test
    void degradedSourcesStillYieldAUsableContext() {
        when(profiles.snapshot(7L)).thenThrow(new IllegalStateException("profile down"));
        when(facts.activeByUser(7L, 8)).thenReturn(List.of());
        when(evidence.recentWeakSkills(7L, ProfileMerger.INTERVIEW_MIN_SCORE, 5))
                .thenReturn(List.of("skill:Redis"));

        PlanContextService.LearnerContext context = service.load(7L);

        assertThat(context.profileSummary()).isEmpty();
        assertThat(context.weakSkills()).containsExactly("Redis");
        assertThat(context.toPromptBlock()).contains("Redis").doesNotContain("画像: ");
    }
}
