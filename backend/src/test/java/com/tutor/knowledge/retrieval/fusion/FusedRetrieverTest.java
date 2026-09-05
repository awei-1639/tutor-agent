package com.tutor.knowledge.retrieval.fusion;

import com.tutor.knowledge.retrieval.fusion.FusedRetriever;
import com.tutor.knowledge.retrieval.graph.GraphStore;
import com.tutor.knowledge.retrieval.graph.GraphExpansionPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FusedRetrieverTest {

    @Test
    void vectorRankingDominatesWhenNoExpansion() {
        Map<String, Double> score = FusedRetriever.fuse(
                List.of("a", "b", "c"), List.of(), List.of(), List.of("a", "b", "c"), false, policy());
        assertThat(score.get("a")).isGreaterThan(score.get("b"));
        assertThat(score.get("b")).isGreaterThan(score.get("c"));
    }

    @Test
    void expandedNodeScoresDecayedByAlpha() {
        // 扩展节点 x 由排名第1的源扩出: score(x) = α/(K+1), 必然低于源本身 1/(K+1)
        Map<String, Double> score = FusedRetriever.fuse(
                List.of("a"), List.of(),
                List.of(new GraphStore.Neighbor("a", "PREREQUISITE", "x", "X",
                        GraphExpansionPolicy.Direction.OUTGOING, 1D, "seed", "active", "unknown")),
                List.of("a"), false, policy());
        assertThat(score.get("x")).isEqualTo(FusedRetriever.ALPHA / (FusedRetriever.RRF_K + 1));
        assertThat(score.get("x")).isLessThan(score.get("a"));
    }

    @Test
    void nodeHitByBothChannelsAccumulates() {
        // b 同时是向量第2名 和 a 的扩展邻居 → 两路得分累加, 应高于仅向量第2名的情形
        Map<String, Double> both = FusedRetriever.fuse(
                List.of("a", "b"), List.of(),
                List.of(new GraphStore.Neighbor("a", "TEACHES", "b", "B",
                        GraphExpansionPolicy.Direction.OUTGOING, 1D, "seed", "active", "unknown")),
                List.of("a", "b"), false, policy());
        Map<String, Double> vecOnly = FusedRetriever.fuse(
                List.of("a", "b"), List.of(), List.of(), List.of("a", "b"), false, policy());
        assertThat(both.get("b")).isGreaterThan(vecOnly.get("b"));
    }

    @Test
    void multipleExpansionSourcesTakeMaxNotSum() {
        // x 被两个源各扩出一次 → 取最优源, 不累加 (首轮评估: 累加导致枢纽节点挤掉gold)
        Map<String, Double> score = FusedRetriever.fuse(
                List.of("a", "b"), List.of(),
                List.of(new GraphStore.Neighbor("a", "TEACHES", "x", "X",
                                GraphExpansionPolicy.Direction.OUTGOING, 1D, "seed", "active", "unknown"),
                        new GraphStore.Neighbor("b", "LEADS_TO", "x", "X",
                                GraphExpansionPolicy.Direction.OUTGOING, 1D, "seed", "active", "unknown")),
                List.of("a", "b"), false, policy());
        assertThat(score.get("x")).isEqualTo(FusedRetriever.ALPHA / (FusedRetriever.RRF_K + 1));
    }

    @Test
    void edgeConfidenceDampensExpansionScore() {
        Map<String, Double> score = FusedRetriever.fuse(
                List.of("a"), List.of(),
                List.of(new GraphStore.Neighbor("a", "PREREQUISITE", "x", "X",
                        GraphExpansionPolicy.Direction.OUTGOING, 0.5D, "review", "active", "unknown")),
                List.of("a"), false, policy());
        assertThat(score.get("x")).isEqualTo(
                FusedRetriever.ALPHA * 0.5D / (FusedRetriever.RRF_K + 1));
    }

    @Test
    void inactiveEdgesAreNotEligibleForFusion() {
        Map<String, Double> score = FusedRetriever.fuse(
                List.of("a"), List.of(),
                List.of(new GraphStore.Neighbor("a", "PREREQUISITE", "x", "X",
                        GraphExpansionPolicy.Direction.OUTGOING, 1.0D, "review", "revoked", "unknown")),
                List.of("a"), false, policy());
        assertThat(score).doesNotContainKey("x");
    }

    @Test
    void resourceClassifierRecognizesMaterialRequestAndRejectsJobRecommendation() {
        assertThat(FusedRetriever.classifyResourceQuery("给我找一份适合学习 RAG 的材料").seeking()).isTrue();
        assertThat(FusedRetriever.classifyResourceQuery("推荐 NLP 岗位").seeking()).isFalse();
    }

    @Test
    void managedDocumentAndExplicitResourceTypeAreNotDampened() {
        GraphStore.Neighbor document = new GraphStore.Neighbor("skill:a", "TEACHES", "internal-1", "课程资料",
                GraphExpansionPolicy.Direction.OUTGOING, 1D, "seed", "active", "resource");
        Map<String, Double> score = FusedRetriever.fuse(
                List.of("skill:a"), List.of("internal-1"), List.of(document), List.of("skill:a"), true,
                policy(), Set.of("internal-1"));
        assertThat(score.get("internal-1")).isGreaterThan(0D);
    }

    @Test
    void resourceDampenLiftsSkillNodeAboveResourceHitForNonSeekingQuery() {
        // q003坏例: 非资源型查询下 res: 切片凭稠密第1名压过技能节点; 对称降权后技能节点反超
        Map<String, Double> undampened = FusedRetriever.fuse(
                List.of("res:linux-basics", "skill:linux-command-line"), List.of(), List.of(),
                List.of("res:linux-basics", "skill:linux-command-line"), false, policy());
        assertThat(undampened.get("res:linux-basics")).isGreaterThan(undampened.get("skill:linux-command-line"));

        Map<String, Double> dampened = FusedRetriever.fuse(
                List.of("res:linux-basics", "skill:linux-command-line"), List.of(), List.of(),
                List.of("res:linux-basics", "skill:linux-command-line"), false, policy(),
                Set.of(), 0.5D);
        assertThat(dampened.get("skill:linux-command-line")).isGreaterThan(dampened.get("res:linux-basics"));
        assertThat(dampened.get("res:linux-basics"))
                .isEqualTo(FusedRetriever.fuse(List.of("res:linux-basics"), List.of(), List.of(),
                        List.of("res:linux-basics"), false, policy(), Set.of(), 0.5D).get("res:linux-basics"));
    }

    @Test
    void resourceDampenDoesNotAffectResourceSeekingQueries() {
        // 资源型查询走既有 dampen 方向, 对称降权不参与
        Map<String, Double> score = FusedRetriever.fuse(
                List.of("res:a", "skill:b"), List.of(), List.of(),
                List.of("res:a", "skill:b"), true, policy(), Set.of(), 0.5D);
        assertThat(score.get("res:a")).isGreaterThan(score.get("skill:b"));
    }

    @Test
    void resourceDampenDefaultKeepsCurrentRanking() {
        Map<String, Double> score = FusedRetriever.fuse(
                List.of("res:a", "skill:b"), List.of(), List.of(),
                List.of("res:a", "skill:b"), false, policy(), Set.of(), 1.0D);
        assertThat(score.get("res:a")).isGreaterThan(score.get("skill:b"));
    }

    @Test
    void knowledgeDocumentChunksAreNotDampenedForNonSeekingQueries() {
        // doc: 切片是概念类查询的知识正主, 不参与对称降权 (判据仅 res: 前缀与 resource_only 集合)
        Map<String, Double> score = FusedRetriever.fuse(
                List.of("doc:u1:0", "skill:b"), List.of(), List.of(),
                List.of("doc:u1:0", "skill:b"), false, policy(), Set.of(), 0.5D);
        assertThat(score.get("doc:u1:0")).isGreaterThan(score.get("skill:b"));

        // resource_kind='resource' 的文档 (nodeType=resource 进入 resourceOnlyIds) 仍被降权
        Map<String, Double> typed = FusedRetriever.fuse(
                List.of("doc:u2:0", "skill:b"), List.of(), List.of(),
                List.of("doc:u2:0", "skill:b"), false, policy(), Set.of(), 0.5D,
                Set.of("doc:u2:0"));
        assertThat(typed.get("skill:b")).isGreaterThan(typed.get("doc:u2:0"));
    }

    @Test
    void optionalChannelFloorKeepsOneCandidatePerAvailableChannel() {
        List<com.tutor.contract.Evidence> ranked = List.of(
                new com.tutor.contract.Evidence("dense", "skill", "d", .9D, null, null, null, null),
                new com.tutor.contract.Evidence("sparse", "skill", "s", .8D, null, null, null, null),
                new com.tutor.contract.Evidence("graph", "skill", "g", .7D, "a->g", null, null, null));
        assertThat(FusedRetriever.ensureChannelFloor(ranked, 3,
                Set.of("dense"), Set.of("sparse"), Set.of("graph")))
                .extracting(com.tutor.contract.Evidence::nodeId)
                .containsExactly("dense", "sparse", "graph");
    }

    @Test
    void mixedLanguageQueryProducesUsefulSeedAliases() {
        assertThat(FusedRetriever.seedAliases("学习RAG和Transformer教程"))
                .contains("rag", "transformer");
    }

    private static GraphExpansionPolicy policy() {
        return GraphExpansionPolicy.of(
                new GraphExpansionPolicy.Rule("PREREQUISITE", GraphExpansionPolicy.Direction.OUTGOING),
                new GraphExpansionPolicy.Rule("TEACHES", GraphExpansionPolicy.Direction.OUTGOING),
                new GraphExpansionPolicy.Rule("LEADS_TO", GraphExpansionPolicy.Direction.OUTGOING),
                new GraphExpansionPolicy.Rule("REQUIRES", GraphExpansionPolicy.Direction.OUTGOING));
    }
}
