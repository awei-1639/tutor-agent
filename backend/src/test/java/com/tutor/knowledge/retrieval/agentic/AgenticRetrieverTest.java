package com.tutor.knowledge.retrieval.agentic;

import com.tutor.knowledge.retrieval.agentic.AgenticRetriever;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import com.tutor.contract.Evidence;
import com.tutor.contract.Intent;
import com.tutor.agent.expert.RoutingPolicy;
import com.tutor.agent.expert.IntentRouter;
import com.tutor.platform.llm.LlmGateway;
import com.tutor.knowledge.retrieval.fusion.FusedRetriever;
import com.tutor.knowledge.retrieval.GraphScope;
import com.tutor.knowledge.retrieval.graph.GraphExpansionPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;

class AgenticRetrieverTest {

    @Test
    @DisplayName("parse: 合法 JSON → sufficient+followup")
    void parseValid() {
        AgenticRetriever.JudgeDecision d = AgenticRetriever.parse(
                "{\"sufficient\":false,\"followup_query\":\"查Python基础\","
                        + "\"missing\":\"变量、函数\"}");
        assertThat(d).isNotNull();
        assertThat(d.sufficient()).isFalse();
        assertThat(d.followupQuery()).isEqualTo("查Python基础");
        assertThat(d.missing()).isEqualTo("变量、函数");
    }

    @Test
    @DisplayName("parse: 充分时 followup 为 null")
    void parseSufficient() {
        AgenticRetriever.JudgeDecision d = AgenticRetriever.parse(
                "{\"sufficient\":true,\"followup_query\":null}");
        assertThat(d).isNotNull();
        assertThat(d.sufficient()).isTrue();
        assertThat(d.followupQuery()).isNull();
        assertThat(d.missing()).isNull();
    }

    @Test
    @DisplayName("parse: 非法 JSON → null (调用方降级)")
    void parseInvalidReturnsNull() {
        assertThat(AgenticRetriever.parse("not json")).isNull();
        assertThat(AgenticRetriever.parse("{}")).isNotNull(); // 空对象合法, sufficient 默认 false
    }

    @Test
    @DisplayName("parse: 缺字段默认 sufficient=false 跳出循环 (因为 followup 也会为空)")
    void parseMissingFields() {
        AgenticRetriever.JudgeDecision d = AgenticRetriever.parse("{\"foo\":\"bar\"}");
        assertThat(d).isNotNull();
        assertThat(d.sufficient()).isFalse();
        assertThat(d.followupQuery()).isNull();
    }

    @Test
    @DisplayName("策略禁止升级时始终只执行单跳")
    void adaptiveRetrievalCanForceSingleHop() {
        FusedRetriever fused = mock(FusedRetriever.class);
        LlmGateway gateway = mock(LlmGateway.class);
        List<Evidence> expected = List.of(new Evidence("skill:x", "skill", "x", 1D, null, null, null, null));
        GraphExpansionPolicy policy = GraphExpansionPolicy.none();
        GraphScope scope = GraphScope.publicOnly();
        when(fused.retrieve(anyString(), eq(5), anyString(), eq(true), eq(true), eq(policy), eq(scope)))
                .thenReturn(outcome(expected));

        AgenticRetriever retriever = new AgenticRetriever(fused, gateway);
        assertThat(retriever.retrieveAdaptiveResult("零基础想学NLP", 5, "trace", false, policy, scope).evidences())
                .isEqualTo(expected);
        verify(fused).retrieve("零基础想学NLP", 5, "trace", true, true, policy, scope);
        verify(gateway, never()).judgeSufficient(anyString(), org.mockito.ArgumentMatchers.anyList(), anyString());
    }

    @Test
    @DisplayName("follow-up 查询会拒绝过长、重复和主题漂移")
    void followupQualityConstraints() {
        java.util.Set<String> seen = new java.util.HashSet<>(List.of("零基础 学 NLP"));
        assertThat(AgenticRetriever.sanitizeFollowup("零基础学 NLP", "零基础学 NLP",
                "NLP 基础概念", seen)).isEqualTo("NLP 基础概念");
        assertThat(AgenticRetriever.sanitizeFollowup("零基础学 NLP", "零基础学 NLP",
                "完全无关的数据库索引", seen)).isNull();
        java.util.Set<String> duplicate = new java.util.HashSet<>(List.of("nlp 基础"));
        assertThat(AgenticRetriever.sanitizeFollowup("零基础学 NLP", "零基础学 NLP",
                "NLP 基础", duplicate)).isNull();
        assertThat(AgenticRetriever.sanitizeFollowup("零基础学 NLP", "零基础学 NLP",
                "NLP".repeat(241), seen)).isNull();
    }

    @Test
    @DisplayName("多跳分数只做绝对上界保护，不抬高低质量跳")
    void hopScoreUsesAbsoluteCeiling() {
        Evidence low = new Evidence("skill:low", "skill", "low", 0.01D,
                null, null, null, null);

        assertThat(AgenticRetriever.boundHopScores(List.of(low)).get(0).score())
                .isEqualTo(0.01D);
    }

    @Test
    @DisplayName("fallback: 使用缺失概念和可达的阶段模板")
    void fallbackUsesMissingAndReachableHints() {
        assertThat(AgenticRetriever.missingFallback(
                "零基础想学神经网络，需要哪些前置知识？", "线性代数、概率统计"))
                .isEqualTo("神经网络 前置知识 线性代数、概率统计");
        assertThat(AgenticRetriever.narrowFallback("零基础想学神经网络，需要哪些前置知识？", 1))
                .endsWith("前置知识 基础概念");
        assertThat(AgenticRetriever.narrowFallback("零基础想学神经网络，需要哪些前置知识？", 2))
                .endsWith("依赖关系 底层原理");
    }

    @Test
    @DisplayName("主题重合: 中文长词与核心主题匹配")
    void followupAcceptsChineseCoreTopicOverlap() {
        assertThat(AgenticRetriever.sanitizeFollowup(
                "神经网络怎么学", "神经网络怎么学", "神经网络 前置知识", java.util.Set.of()))
                .isEqualTo("神经网络 前置知识");
    }

    @Test
    @DisplayName("后续跳次扩展上一跳的图前沿并保留连续路径")
    void expandsPreviousGraphFrontier() {
        FusedRetriever fused = mock(FusedRetriever.class);
        LlmGateway gateway = mock(LlmGateway.class);
        GraphExpansionPolicy policy = GraphExpansionPolicy.forFacets(
                List.of(RoutingPolicy.RetrievalFacet.LEARNING), IntentRouter.RetrievalHint.MULTI_CANDIDATE);
        GraphScope scope = GraphScope.publicOnly();
        Evidence first = new Evidence("skill:a", "skill", "A", 0.8D,
                "skill:a -[PREREQUISITE]-> skill:b", null, "seed", null);
        Evidence second = new Evidence("skill:b", "skill", "B", 0.7D, null, null, "seed", null);
        Evidence third = new Evidence("skill:c", "skill", "C", 0.4D,
                "skill:a -[PREREQUISITE]-> skill:b | skill:b -[PREREQUISITE]-> skill:c",
                null, "seed", null);
        when(fused.retrieve(anyString(), anyInt(), anyString(), any(Boolean.class), any(Boolean.class),
                any(GraphExpansionPolicy.class), any(GraphScope.class)))
                .thenReturn(outcome(List.of(first)), outcome(List.of(second)));
        when(fused.expandFrontier(anyList(), anyInt(), any(GraphExpansionPolicy.class), any(GraphScope.class)))
                .thenReturn(List.of(third));
        when(gateway.judgeSufficientWithEvidence(anyString(), anyString(), anyList(), anyString()))
                .thenReturn("{\"sufficient\":false,\"followup_query\":\"NLP 基础\"}")
                .thenReturn("{\"sufficient\":true,\"followup_query\":null}");

        AgenticRetriever retriever = new AgenticRetriever(fused, gateway);
        AgenticRetriever.RetrievalResult result = retriever.retrieveAdaptiveResult(
                "NLP 学习路径", 5, "trace", true, policy, scope);

        assertThat(result.hops()).isEqualTo(2);
        assertThat(result.stopReason()).isEqualTo("judge_sufficient");
        assertThat(result.evidences()).extracting(Evidence::nodeId).contains("skill:c");
        verify(fused).expandFrontier(anyList(), anyInt(), eq(policy), eq(scope));
        verify(gateway).judgeSufficientWithEvidence(eq("NLP 学习路径"), eq("NLP 基础"),
                anyList(), eq("trace"));
    }

    @Test
    @DisplayName("多跳最多执行三跳")
    void enforcesThreeHopLimit() {
        FusedRetriever fused = mock(FusedRetriever.class);
        LlmGateway gateway = mock(LlmGateway.class);
        GraphExpansionPolicy policy = GraphExpansionPolicy.forFacets(
                List.of(RoutingPolicy.RetrievalFacet.LEARNING), IntentRouter.RetrievalHint.MULTI_CANDIDATE);
        Evidence evidence = new Evidence("skill:nlp", "skill", "NLP", 1D, null, null, "seed", null);
        when(fused.retrieve(anyString(), anyInt(), anyString(), any(Boolean.class), any(Boolean.class),
                any(GraphExpansionPolicy.class), any(GraphScope.class)))
                .thenReturn(outcome(List.of(evidence)));
        when(gateway.judgeSufficientWithEvidence(anyString(), anyString(), anyList(), anyString()))
                .thenReturn("{\"sufficient\":false,\"followup_query\":\"NLP 基础\"}")
                .thenReturn("{\"sufficient\":false,\"followup_query\":\"NLP 进阶\"}");

        AgenticRetriever.RetrievalResult result = new AgenticRetriever(fused, gateway)
                .retrieveAdaptiveResult("NLP 学习路径", 5, "trace", true, policy, GraphScope.publicOnly());

        assertThat(result.hops()).isEqualTo(3);
        assertThat(result.stopReason()).isEqualTo("max_hops");
        verify(fused, org.mockito.Mockito.times(3)).retrieve(anyString(), anyInt(), anyString(),
                any(Boolean.class), any(Boolean.class), any(GraphExpansionPolicy.class), any(GraphScope.class));
    }

    private static FusedRetriever.RetrievalOutcome outcome(List<Evidence> evidences) {
        return new FusedRetriever.RetrievalOutcome(evidences, FusedRetriever.RetrievalTelemetry.empty());
    }
}
