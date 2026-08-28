package com.tutor.expert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.Intent;
import com.tutor.contract.Purpose;
import com.tutor.llm.LlmGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentRouterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesValidIntent() {
        var resume = IntentRouter.parseDecision(
                "{\"scope\":\"in_scope\",\"intent\":\"resume\",\"retrieval_facets\":[\"career\"],\"retrieval_hint\":\"single\",\"confidence\":0.8}", mapper);
        var outOfScope = IntentRouter.parseDecision(
                "{\"scope\":\"out_of_scope\",\"intent\":\"OUT_OF_SCOPE\",\"retrieval_facets\":[],\"retrieval_hint\":\"none\",\"confidence\":0.8}", mapper);
        assertThat(resume.intent()).isEqualTo(Intent.RESUME);
        assertThat(resume.retrievalFacets()).containsExactly(RoutingPolicy.RetrievalFacet.CAREER);
        assertThat(outOfScope.intent()).isEqualTo(Intent.OUT_OF_SCOPE);
        assertThat(resume.degraded()).isFalse();
    }

    @Test
    void unknownValueFallsBackSafelyAndMarksDegraded() {
        var decision = IntentRouter.parseDecision(
                "{\"scope\":\"in_scope\",\"intent\":\"banana\",\"retrieval_facets\":[],\"retrieval_hint\":\"single\",\"confidence\":0.8}", mapper);
        assertThat(decision.intent()).isEqualTo(Intent.CHAT);
        assertThat(decision.degraded()).isTrue();
        assertThat(decision.reasonCodes()).contains("UNKNOWN_INTENT");
    }

    @Test
    void missingOrUnknownFacetMarksTheDecisionDegraded() {
        var missing = IntentRouter.parseDecision(
                "{\"scope\":\"in_scope\",\"intent\":\"chat\",\"retrieval_hint\":\"single\",\"confidence\":0.8}", mapper);
        var unknown = IntentRouter.parseDecision(
                "{\"scope\":\"in_scope\",\"intent\":\"chat\",\"retrieval_facets\":[\"banana\"],\"retrieval_hint\":\"single\",\"confidence\":0.8}", mapper);

        assertThat(missing.reasonCodes()).contains("MISSING_RETRIEVAL_FACETS");
        assertThat(unknown.reasonCodes()).contains("UNKNOWN_RETRIEVAL_FACET");
        assertThat(missing.degraded()).isTrue();
        assertThat(unknown.degraded()).isTrue();
    }

    @Test
    void malformedJsonFallsBackToChat() {
        assertThat(IntentRouter.parseDecision("not json", mapper).intent()).isEqualTo(Intent.CHAT);
    }

    @Test
    void parsesStructuredDecisionAndBoundsConfidence() {
        IntentRouter.RouteDecision decision = IntentRouter.parseDecision("""
                {"scope":"in_scope","intent":"planning","retrieval_facets":["learning"],"retrieval_hint":"multi_candidate",
                 "confidence":1.7,"reason_codes":["PATH_REQUEST"]}
                """, mapper);
        assertThat(decision.scope()).isEqualTo(IntentRouter.Scope.IN_SCOPE);
        assertThat(decision.intent()).isEqualTo(Intent.PLANNING);
        assertThat(decision.retrievalHint()).isEqualTo(IntentRouter.RetrievalHint.MULTI_CANDIDATE);
        assertThat(decision.confidence()).isEqualTo(1D);
        assertThat(decision.degraded()).isTrue();
        assertThat(decision.reasonCodes()).contains("CONFIDENCE_OUT_OF_RANGE");
        assertThat(decision.reasonCodes()).contains("PATH_REQUEST", "CONFIDENCE_OUT_OF_RANGE");
    }

    @Test
    void rejectsInconsistentScopeIntentHintCombination() {
        var decision = IntentRouter.parseDecision("""
                {"scope":"out_of_scope","intent":"planning","retrieval_facets":["learning"],"retrieval_hint":"multi_candidate","confidence":0.8}
                """, mapper);

        assertThat(decision.degraded()).isTrue();
        assertThat(decision.reasonCodes()).contains("ROUTE_SCOPE_INTENT_CONFLICT", "ROUTE_SCOPE_HINT_CONFLICT");
    }

    @Test
    void mixedIntentRequiresExplicitSubIntents() {
        var decision = IntentRouter.parseDecision("""
                {"scope":"in_scope","intent":"mixed","intents":["resume","interview"],"retrieval_facets":["career","learning"],
                 "retrieval_hint":"single","confidence":0.8}
                """, mapper);

        assertThat(decision.degraded()).isFalse();
        assertThat(decision.subIntents()).containsExactly(Intent.RESUME, Intent.INTERVIEW);
    }

    @Test
    void routeDecisionRejectsIncompleteMixedSubIntents() {
        var decision = new IntentRouter.RouteDecision(
                IntentRouter.Scope.IN_SCOPE, Intent.MIXED, List.of(Intent.RESUME), List.of(RoutingPolicy.RetrievalFacet.CAREER),
                IntentRouter.RetrievalHint.SINGLE, 0.8, null, List.of(), false);

        assertThat(decision.degraded()).isTrue();
        assertThat(decision.reasonCodes()).contains("MIXED_SUBINTENTS_REQUIRED");
    }

    @Test
    void routeDecisionInjectsCalibratedConfidence() {
        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.chatJson(eq(Purpose.ROUTER), anyList(), eq("trace"), isNull(), eq(1))).thenReturn(
                "{\"scope\":\"out_of_scope\",\"intent\":\"out_of_scope\",\"intents\":[],"
                        + "\"alternative_intent\":\"none\",\"alternative_confidence\":0,"
                        + "\"ambiguity_flags\":[],\"retrieval_facets\":[],"
                        + "\"retrieval_hint\":\"none\",\"confidence\":0.75,\"reason_codes\":[]}");
        var model = new RoutingConfidenceCalibrator.CalibrationModel("test-v1", List.of(
                new RoutingConfidenceCalibrator.CalibrationPoint(0D, 0.05D),
                new RoutingConfidenceCalibrator.CalibrationPoint(1D, 0.90D)));
        var router = new IntentRouter(gateway, RoutingConfidenceCalibrator.forTest(model));

        var decision = router.routeDecision("天气怎么样", List.of(), "trace");

        assertThat(decision.calibratedConfidence()).isCloseTo(0.6875D,
                org.assertj.core.data.Offset.offset(0.000001D));
        assertThat(decision.degraded()).isFalse();
    }

    @Test
    void invalidDecisionUsesDegradedSafeDefaults() {
        IntentRouter.RouteDecision decision = IntentRouter.parseDecision("not json", mapper);
        assertThat(decision.intent()).isEqualTo(Intent.CHAT);
        assertThat(decision.scope()).isEqualTo(IntentRouter.Scope.UNCERTAIN);
        assertThat(decision.retrievalHint()).isEqualTo(IntentRouter.RetrievalHint.SINGLE);
        assertThat(decision.degraded()).isTrue();
    }

    @Test
    void preservesModelAmbiguityAsASeparateRoutingSignal() {
        var decision = IntentRouter.parseDecision("""
                {"scope":"in_scope","intent":"planning","alternative_intent":"resume",
                 "ambiguity_flags":["岗位匹配和学习规划同时出现"],"retrieval_facets":["learning","career"],
                 "retrieval_hint":"multi_candidate","confidence":0.82}
                """, mapper);

        assertThat(decision.degraded()).isFalse();
        assertThat(decision.reasonCodes()).contains("MODEL_AMBIGUITY", "MODEL_COMPETING_INTENT");
    }

    @Test
    void parsesAlternativeConfidenceAndMarksCloseIntents() {
        var decision = IntentRouter.parseDecision("""
                {"scope":"in_scope","intent":"planning","alternative_intent":"resume",
                 "alternative_confidence":0.74,"retrieval_facets":["learning","career"],
                 "retrieval_hint":"multi_candidate","confidence":0.82}
                """, mapper);

        assertThat(decision.alternativeConfidence()).isEqualTo(0.74D);
        assertThat(decision.reasonCodes()).contains("MODEL_CLOSE_INTENTS");
    }
}
