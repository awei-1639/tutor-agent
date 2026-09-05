package com.tutor.memory.application;

import com.tutor.memory.local.EpisodeStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryMergePolicyTest {
    private final MemoryMergePolicy policy = new MemoryMergePolicy(30);

    @Test
    void removesEmptyCandidatesBeforeMerging() {
        List<EpisodeStore.Episode> candidates = new ArrayList<>();
        candidates.add(null);
        candidates.addAll(List.of(
                new EpisodeStore.Episode(1, 7, null, "", List.of(), List.of()),
                new EpisodeStore.Episode(2, 7, null, "有效记忆", List.of(), List.of())));
        assertThat(policy.safeEpisodes(candidates))
                .extracting(EpisodeStore.Episode::summary)
                .containsExactly("有效记忆");
    }

    @Test
    void mergesCandidatesByRelevanceAndRemovesNearDuplicates() {
        List<EpisodeStore.Episode> merged = policy.merge(
                List.of(new EpisodeStore.Episode(1, 7, null, "学习 RAG", List.of(), List.of(), 0.2D)),
                List.of(new EpisodeStore.Episode(2, 7, null, "学习RAG", List.of(), List.of(), 0.9D),
                        new EpisodeStore.Episode(3, 7, null, "准备面试", List.of(), List.of(), 0.8D)));

        assertThat(merged).extracting(EpisodeStore.Episode::summary)
                .containsExactly("准备面试", "学习 RAG");
    }
}
