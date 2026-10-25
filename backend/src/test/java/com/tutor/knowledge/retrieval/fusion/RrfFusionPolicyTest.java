package com.tutor.knowledge.retrieval.fusion;

import com.tutor.knowledge.retrieval.graph.GraphExpansionPolicy;
import com.tutor.knowledge.retrieval.graph.GraphStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RrfFusionPolicyTest {

    /** 自环 (dst==src) 不得给已排位节点叠扩展分 (诊断报告 2026-09-10 修复清单 #8)。 */
    @Test
    void selfLoopNeighborsDoNotInflateSourceScore() {
        GraphExpansionPolicy policy = GraphExpansionPolicy.forFacets(
                List.of(com.tutor.agent.expert.RoutingPolicy.RetrievalFacet.LEARNING),
                com.tutor.agent.expert.IntentRouter.RetrievalHint.SINGLE);
        GraphStore.Neighbor selfLoop = new GraphStore.Neighbor(
                "skill:a", "PREREQUISITE", "skill:a", "A", GraphExpansionPolicy.Direction.INCOMING,
                1.0D, "seed", "active", "skill");
        GraphStore.Neighbor real = new GraphStore.Neighbor(
                "skill:a", "PREREQUISITE", "skill:b", "B", GraphExpansionPolicy.Direction.INCOMING,
                1.0D, "seed", "active", "skill");

        Map<String, Double> withLoop = RrfFusionPolicy.fuse(
                List.of("skill:a"), List.of(), List.of(selfLoop), List.of("skill:a"),
                false, policy, null, 60, 1.0D, 0.5D, 1.0D, 1.0D, null);
        Map<String, Double> withoutLoop = RrfFusionPolicy.fuse(
                List.of("skill:a"), List.of(), List.of(), List.of("skill:a"),
                false, policy, null, 60, 1.0D, 0.5D, 1.0D, 1.0D, null);

        assertThat(withLoop.get("skill:a"))
                .as("自环不改变源节点得分")
                .isEqualTo(withoutLoop.get("skill:a"));
    }

    /** 正常邻居照旧获得扩展分, 过滤只针对自环。 */
    @Test
    void realNeighborsStillReceiveExpansionScore() {
        GraphExpansionPolicy policy = GraphExpansionPolicy.forFacets(
                List.of(com.tutor.agent.expert.RoutingPolicy.RetrievalFacet.LEARNING),
                com.tutor.agent.expert.IntentRouter.RetrievalHint.SINGLE);
        GraphStore.Neighbor real = new GraphStore.Neighbor(
                "skill:a", "PREREQUISITE", "skill:b", "B", GraphExpansionPolicy.Direction.INCOMING,
                1.0D, "seed", "active", "skill");

        Map<String, Double> scores = RrfFusionPolicy.fuse(
                List.of("skill:a"), List.of(), List.of(real), List.of("skill:a"),
                false, policy, null, 60, 1.0D, 0.5D, 1.0D, 1.0D, null);

        assertThat(scores).containsKey("skill:b");
        assertThat(scores.get("skill:b")).isPositive();
    }
}
