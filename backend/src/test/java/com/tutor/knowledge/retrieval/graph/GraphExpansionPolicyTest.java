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
}
