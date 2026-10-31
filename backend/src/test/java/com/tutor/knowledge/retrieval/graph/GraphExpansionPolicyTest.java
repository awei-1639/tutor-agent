package com.tutor.knowledge.retrieval.graph;

import com.tutor.agent.expert.IntentRouter;
import com.tutor.agent.expert.RoutingPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphExpansionPolicyTest {
    @Test
    void learningFacetUsesIncomingPrerequisiteEdges() {
        GraphExpansionPolicy policy = GraphExpansionPolicy.forFacets(
                List.of(RoutingPolicy.RetrievalFacet.LEARNING), IntentRouter.RetrievalHint.MULTI_CANDIDATE);

        assertThat(policy.relationDescriptions()).containsExactly("PREREQUISITE<-", "TEACHES<-");
    }

    @Test
    void resourceFacetUsesIncomingTeachesEdges() {
        GraphExpansionPolicy policy = GraphExpansionPolicy.forFacets(
                List.of(RoutingPolicy.RetrievalFacet.RESOURCE), IntentRouter.RetrievalHint.SINGLE);

        assertThat(policy.relationDescriptions()).containsExactly("TEACHES<-");
    }

    @Test
    void careerFacetUsesCareerRelations() {
        GraphExpansionPolicy policy = GraphExpansionPolicy.forFacets(
                List.of(RoutingPolicy.RetrievalFacet.CAREER), IntentRouter.RetrievalHint.SINGLE);

        assertThat(policy.relationDescriptions()).containsExactly("REQUIRES->", "LEADS_TO->");
    }

    @Test
    void mixedFacetsPreserveBothBranches() {
        GraphExpansionPolicy policy = GraphExpansionPolicy.forFacets(
                List.of(RoutingPolicy.RetrievalFacet.LEARNING, RoutingPolicy.RetrievalFacet.CAREER),
                IntentRouter.RetrievalHint.MULTI_CANDIDATE);

        assertThat(policy.relationDescriptions()).containsExactly(
                "PREREQUISITE<-", "TEACHES<-", "REQUIRES->", "LEADS_TO->");
    }

    @Test
    void emptyFacetDisablesGraphInsteadOfInferringFromKeywords() {
        GraphExpansionPolicy policy = GraphExpansionPolicy.forFacets(List.of(), IntentRouter.RetrievalHint.SINGLE);

        assertThat(policy.enabled()).isFalse();
    }

    /**
     * multi_hop 二轮诊断 (2026-10-31): 评测集 gold 是前置关系的传递闭包，单跳覆盖不到
     * 「前置的前置」。LEARNING 下的 PREREQUISITE 规则必须走两跳。
     */
    @Test
    void learningPrerequisiteRuleCoversTwoHops() {
        GraphExpansionPolicy policy = GraphExpansionPolicy.forFacets(
                List.of(RoutingPolicy.RetrievalFacet.LEARNING), IntentRouter.RetrievalHint.MULTI_CANDIDATE);

        assertThat(policy.ruleFor("PREREQUISITE", GraphExpansionPolicy.Direction.INCOMING).maxHops())
                .isEqualTo(2);
        // 其余关系保持单跳: REQUIRES/TEACHES 没有传递语义, 走两跳只会放大噪声。
        assertThat(policy.ruleFor("TEACHES", GraphExpansionPolicy.Direction.INCOMING).maxHops())
                .isEqualTo(1);
        assertThat(policy.policyDescriptions()).contains("PREREQUISITE<-@0.80/2hop");
    }

    @Test
    void maxHopsIsBoundedAsSafetyBoundary() {
        assertThat(new GraphExpansionPolicy.Rule(
                "PREREQUISITE", GraphExpansionPolicy.Direction.INCOMING, 0.8D, 1.0D, 1).maxHops())
                .isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new GraphExpansionPolicy.Rule(
                        "PREREQUISITE", GraphExpansionPolicy.Direction.INCOMING, 0.8D, 1.0D, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxHops");
    }
}
