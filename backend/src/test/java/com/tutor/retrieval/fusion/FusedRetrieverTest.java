package com.tutor.retrieval.fusion;

import com.tutor.retrieval.fusion.FusedRetriever;
import com.tutor.retrieval.graph.GraphStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FusedRetrieverTest {

    @Test
    void vectorRankingDominatesWhenNoExpansion() {
        Map<String, Double> score = FusedRetriever.fuse(
                List.of("a", "b", "c"), List.of(), List.of("a", "b", "c"), false);
        assertThat(score.get("a")).isGreaterThan(score.get("b"));
        assertThat(score.get("b")).isGreaterThan(score.get("c"));
    }

    @Test
    void expandedNodeScoresDecayedByAlpha() {
        // 扩展节点 x 由排名第1的源扩出: score(x) = α/(K+1), 必然低于源本身 1/(K+1)
        Map<String, Double> score = FusedRetriever.fuse(
                List.of("a"),
                List.of(new GraphStore.Neighbor("a", "PREREQUISITE", "x", "X")),
                List.of("a"), false);
        assertThat(score.get("x")).isEqualTo(FusedRetriever.ALPHA / (FusedRetriever.RRF_K + 1));
        assertThat(score.get("x")).isLessThan(score.get("a"));
    }

    @Test
    void nodeHitByBothChannelsAccumulates() {
        // b 同时是向量第2名 和 a 的扩展邻居 → 两路得分累加, 应高于仅向量第2名的情形
        Map<String, Double> both = FusedRetriever.fuse(
                List.of("a", "b"),
                List.of(new GraphStore.Neighbor("a", "TEACHES", "b", "B")),
                List.of("a", "b"), false);
        Map<String, Double> vecOnly = FusedRetriever.fuse(
                List.of("a", "b"), List.of(), List.of("a", "b"), false);
        assertThat(both.get("b")).isGreaterThan(vecOnly.get("b"));
    }

    @Test
    void multipleExpansionSourcesTakeMaxNotSum() {
        // x 被两个源各扩出一次 → 取最优源, 不累加 (首轮评估: 累加导致枢纽节点挤掉gold)
        Map<String, Double> score = FusedRetriever.fuse(
                List.of("a", "b"),
                List.of(new GraphStore.Neighbor("a", "TEACHES", "x", "X"),
                        new GraphStore.Neighbor("b", "LEADS_TO", "x", "X")),
                List.of("a", "b"), false);
        assertThat(score.get("x")).isEqualTo(FusedRetriever.ALPHA / (FusedRetriever.RRF_K + 1));
    }
}
