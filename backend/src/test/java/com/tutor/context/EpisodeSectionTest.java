package com.tutor.context;

import com.tutor.context.sections.EpisodeSection;
import com.tutor.memory.local.EpisodeStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EpisodeSectionTest {

    private final EpisodeSection section = new EpisodeSection();
    private final TokenBudget budget = new TokenBudget();

    @Test
    void rendersRelevantEpisodesAsNonEvidenceContext() {
        var episode = new EpisodeStore.Episode(
                1L, 42L, 7L,
                "用户正在准备 Java 后端岗位，计划补齐 Spring 基础。",
                List.of("Java", "Spring"),
                List.of("完成 Spring 实战项目"));

        String rendered = section.render(
                new TurnContextView(Map.of(), List.of(), null, List.of(episode)), budget);

        assertThat(rendered).contains("用户过去的相关经历（仅作个性化参考）")
                .contains("Java", "Spring", "完成 Spring 实战项目");
        assertThat(rendered).doesNotContain("[S1]");
    }

    @Test
    void emptyEpisodesDoNotChangePrompt() {
        String rendered = section.render(
                new TurnContextView(Map.of(), List.of(), null, List.of()), budget);

        assertThat(rendered).isEmpty();
    }
}
