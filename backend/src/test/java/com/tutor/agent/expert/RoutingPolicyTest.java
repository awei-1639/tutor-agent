package com.tutor.agent.expert;

import com.tutor.contract.Intent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingPolicyTest {
    private final RoutingPolicy policy = new RoutingPolicy();

    @Test
    void onlyHighConfidenceOutOfScopeSkipsRetrieval() {
        var plan = policy.plan(new IntentRouter.RouteDecision(
                IntentRouter.Scope.OUT_OF_SCOPE, Intent.OUT_OF_SCOPE, List.of(), List.of(), IntentRouter.RetrievalHint.NONE,
                0.95, 0.95, List.of("CLEARLY_UNRELATED"), false));
        assertThat(plan.skipRetrieval()).isTrue();
        assertThat(plan.intent()).isEqualTo(Intent.OUT_OF_SCOPE);
    }

    @Test
    void lowConfidenceOutOfScopeFallsBackToSingleHopChat() {
        var plan = policy.plan(new IntentRouter.RouteDecision(
                IntentRouter.Scope.OUT_OF_SCOPE, Intent.OUT_OF_SCOPE, List.of(), List.of(), IntentRouter.RetrievalHint.MULTI_CANDIDATE,
                0.60, null, List.of("AMBIGUOUS"), false));
        assertThat(plan.skipRetrieval()).isFalse();
        assertThat(plan.intent()).isEqualTo(Intent.CHAT);
        assertThat(plan.allowMultiHopEscalation()).isFalse();
    }

    @Test
    void modelConfidenceAloneCannotSkipRetrievalWithoutCalibration() {
        var plan = policy.plan(new IntentRouter.RouteDecision(
                IntentRouter.Scope.OUT_OF_SCOPE, Intent.OUT_OF_SCOPE, List.of(), List.of(), IntentRouter.RetrievalHint.NONE,
                0.99, null, List.of("CLEARLY_UNRELATED"), false));

        assertThat(plan.skipRetrieval()).isFalse();
        assertThat(plan.intent()).isEqualTo(Intent.CHAT);
    }

    @Test
    void inScopeMultiCandidateCanEscalateAfterFirstHop() {
        var plan = policy.plan(new IntentRouter.RouteDecision(
                IntentRouter.Scope.IN_SCOPE, Intent.PLANNING, List.of(Intent.PLANNING), List.of(RoutingPolicy.RetrievalFacet.LEARNING),
                IntentRouter.RetrievalHint.MULTI_CANDIDATE, 0.88, null, List.of("PATH_REQUEST"), false));
        assertThat(plan.skipRetrieval()).isFalse();
        assertThat(plan.allowMultiHopEscalation()).isTrue();
        assertThat(plan.intent()).isEqualTo(Intent.PLANNING);
        assertThat(plan.retrievalFacets()).containsExactly(RoutingPolicy.RetrievalFacet.LEARNING);
    }

    @Test
    void lowConfidenceInScopeMultiCandidateFallsBackToSingleHop() {
        var plan = policy.plan(new IntentRouter.RouteDecision(
                IntentRouter.Scope.IN_SCOPE, Intent.PLANNING, List.of(Intent.PLANNING),
                List.of(RoutingPolicy.RetrievalFacet.LEARNING),
                IntentRouter.RetrievalHint.MULTI_CANDIDATE, 0.60, null, List.of("AMBIGUOUS"), false));

        assertThat(plan.allowMultiHopEscalation()).isFalse();
        assertThat(plan.retrievalHint()).isEqualTo(IntentRouter.RetrievalHint.SINGLE);
        assertThat(plan.skipRetrieval()).isFalse();
    }

    @Test
    void modelReportedAmbiguityBlocksMultiHopEvenWhenConfidenceIsHigh() {
        var plan = policy.plan(new IntentRouter.RouteDecision(
                IntentRouter.Scope.IN_SCOPE, Intent.PLANNING, List.of(Intent.PLANNING),
                List.of(RoutingPolicy.RetrievalFacet.LEARNING, RoutingPolicy.RetrievalFacet.CAREER),
                IntentRouter.RetrievalHint.MULTI_CANDIDATE, 0.92, null,
                List.of("MODEL_AMBIGUITY"), false));

        assertThat(plan.allowMultiHopEscalation()).isFalse();
        assertThat(plan.retrievalHint()).isEqualTo(IntentRouter.RetrievalHint.SINGLE);
    }

    @Test
    void competingAlternativeIntentBlocksMultiHopWithoutAmbiguityFlag() {
        var plan = policy.plan(new IntentRouter.RouteDecision(
                IntentRouter.Scope.IN_SCOPE, Intent.PLANNING, List.of(Intent.PLANNING),
                List.of(RoutingPolicy.RetrievalFacet.LEARNING),
                IntentRouter.RetrievalHint.MULTI_CANDIDATE, 0.90, null,
                List.of("MODEL_COMPETING_INTENT"), false));

        assertThat(plan.allowMultiHopEscalation()).isFalse();
        assertThat(plan.retrievalHint()).isEqualTo(IntentRouter.RetrievalHint.SINGLE);
    }

    @Test
    void mediumConfidenceCompetingIntentRequestsClarification() {
        var decision = new IntentRouter.RouteDecision(
                IntentRouter.Scope.IN_SCOPE, Intent.PLANNING, List.of(Intent.PLANNING),
                List.of(RoutingPolicy.RetrievalFacet.LEARNING), IntentRouter.RetrievalHint.SINGLE,
                0.65, null, List.of("MODEL_COMPETING_INTENT"), false);

        assertThat(policy.shouldClarify(decision)).isTrue();
        assertThat(policy.clarificationQuestion(decision)).contains("学习计划");
    }

    @Test
    void highConfidenceCompetingIntentKeepsSingleHopWithoutClarification() {
        var decision = new IntentRouter.RouteDecision(
                IntentRouter.Scope.IN_SCOPE, Intent.PLANNING, List.of(Intent.PLANNING),
                List.of(RoutingPolicy.RetrievalFacet.LEARNING), IntentRouter.RetrievalHint.SINGLE,
                0.85, null, List.of("MODEL_COMPETING_INTENT"), false);

        assertThat(policy.shouldClarify(decision)).isFalse();
    }

    @Test
    void narrowAlternativeMarginBlocksMultiHop() {
        var decision = new IntentRouter.RouteDecision(
                IntentRouter.Scope.IN_SCOPE, Intent.PLANNING, List.of(Intent.PLANNING),
                List.of(RoutingPolicy.RetrievalFacet.LEARNING), IntentRouter.RetrievalHint.MULTI_CANDIDATE,
                0.90, null, 0.82, List.of(), false);

        var plan = policy.plan(decision);

        assertThat(plan.allowMultiHopEscalation()).isFalse();
        assertThat(plan.retrievalHint()).isEqualTo(IntentRouter.RetrievalHint.SINGLE);
    }

    @Test
    void domainSignalPreventsHighConfidenceOutOfScopeSkip() {
        var plan = policy.plan(new IntentRouter.RouteDecision(
                IntentRouter.Scope.OUT_OF_SCOPE, Intent.OUT_OF_SCOPE, List.of(), List.of(), IntentRouter.RetrievalHint.NONE,
                0.99, 0.99, List.of("CLEARLY_UNRELATED"), false),
                "我的简历投大模型岗位应该怎么改");
        assertThat(plan.skipRetrieval()).isFalse();
        assertThat(plan.intent()).isEqualTo(Intent.CHAT);
        assertThat(plan.reasonCodes()).contains("DOMAIN_BOUNDARY_SIGNAL");
        assertThat(plan.retrievalFacets()).isEmpty();
    }

    @Test
    void resumeIntentCreatesCareerFacetWithoutGraphPolicyParsingTheQuestion() {
        var plan = policy.plan(new IntentRouter.RouteDecision(
                IntentRouter.Scope.IN_SCOPE, Intent.RESUME, List.of(Intent.RESUME), List.of(RoutingPolicy.RetrievalFacet.CAREER),
                IntentRouter.RetrievalHint.SINGLE, 0.88, null, List.of(), false),
                "这个 JD 值得投吗");

        assertThat(plan.retrievalFacets()).containsExactly(RoutingPolicy.RetrievalFacet.CAREER);
    }
}
