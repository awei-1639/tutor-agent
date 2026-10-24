package com.tutor.agent.tool;

import com.tutor.contract.Intent;
import com.tutor.agent.expert.IntentRouter;
import com.tutor.agent.expert.RoutingPolicy;
import com.tutor.knowledge.retrieval.agentic.AgenticRetriever;
import com.tutor.knowledge.retrieval.fusion.FusedRetriever;
import com.tutor.knowledge.retrieval.graph.GraphExpansionPolicy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 路由决策缓存的门禁行为: 默认关闭, 评测开启后同 query 只路由一次。 */
class RetrievalToolServiceTest {
    private static final String QUERY = "腾讯做对话系统的NLP算法工程师，得会啥技能啊？";

    private final FusedRetriever retriever = mock(FusedRetriever.class);
    private final AgenticRetriever agenticRetriever = mock(AgenticRetriever.class);
    private final IntentRouter router = mock(IntentRouter.class);
    private final RoutingPolicy routingPolicy = mock(RoutingPolicy.class);
    private final RoutingPolicy.ExecutionPlan plan = mock(RoutingPolicy.ExecutionPlan.class);

    @Test
    void cacheDisabledRoutesEveryRequest() {
        stubHappyPath();
        RetrievalToolService service = service(false, false);

        service.retrieve(retrieve(QUERY), "trace-1");
        service.retrieve(retrieve(QUERY), "trace-2");

        verify(router, times(2)).routeDecision(anyString(), any(), anyString());
    }

    @Test
    void cacheEnabledRoutesSameQueryOnlyOnce() {
        stubHappyPath();
        RetrievalToolService service = service(true, false);

        service.retrieve(retrieve(QUERY), "trace-1");
        service.retrieve(retrieve(QUERY), "trace-2");

        verify(router, times(1)).routeDecision(anyString(), any(), anyString());
    }

    @Test
    void cacheEnabledStillRoutesDistinctQueries() {
        stubHappyPath();
        RetrievalToolService service = service(true, false);

        service.retrieve(retrieve(QUERY), "trace-1");
        service.retrieve(retrieve("前端工程师要会什么"), "trace-2");

        verify(router, times(2)).routeDecision(anyString(), any(), anyString());
    }

    /** 工具侧只需要 facet, 规则判定后一次路由调用都不该发生 (这是这次要省的 meta-call)。 */
    @Test
    void facetRulesSkipRouterCallsEntirely() {
        stubHappyPath();
        RetrievalToolService service = service(false, true);

        service.retrieve(retrieve(QUERY), "trace-1");
        service.retrieve(retrieve("前端工程师要会什么"), "trace-2");

        verify(router, times(0)).routeDecision(anyString(), any(), anyString());
    }

    /** 规则判出的 facet 必须真正落到图扩展策略上, 否则省下的调用换来的是空策略。 */
    @Test
    void facetRulesDriveGraphExpansion() {
        stubHappyPath();
        RetrievalToolService service = service(false, true);
        ArgumentCaptor<GraphExpansionPolicy> policy = ArgumentCaptor.forClass(GraphExpansionPolicy.class);

        service.retrieve(retrieve("Transformer的面试题有哪些"), "trace-1");
        verify(retriever).retrieve(anyString(), anyInt(), anyString(), anyBoolean(), anyBoolean(),
                policy.capture(), any());
        assertThat(policy.getValue().rules()).as("面试题要走技能依赖扩展").isNotEmpty();

        service.retrieve(retrieve("什么是检索增强生成"), "trace-2");
        verify(retriever, times(2)).retrieve(anyString(), anyInt(), anyString(), anyBoolean(), anyBoolean(),
                policy.capture(), any());
        assertThat(policy.getValue().rules()).as("纯概念题不做图扩展").isEmpty();
    }

    private RetrievalToolService service(boolean cacheEnabled, boolean facetRulesEnabled) {
        return new RetrievalToolService(retriever, agenticRetriever, router, routingPolicy,
                cacheEnabled, facetRulesEnabled);
    }

    private void stubHappyPath() {
        IntentRouter.RouteDecision decision = new IntentRouter.RouteDecision(
                IntentRouter.Scope.IN_SCOPE, Intent.CHAT, List.of(), List.of(),
                IntentRouter.RetrievalHint.SINGLE, 0.9D, null, List.of(), false);
        when(router.routeDecision(anyString(), any(), anyString())).thenReturn(decision);
        when(routingPolicy.plan(any(), anyString())).thenReturn(plan);
        when(plan.retrievalFacets()).thenReturn(List.of());
        when(plan.retrievalHint()).thenReturn(IntentRouter.RetrievalHint.SINGLE);
        when(retriever.retrieve(anyString(), anyInt(), anyString(), anyBoolean(), anyBoolean(), any(), any()))
                .thenReturn(new FusedRetriever.RetrievalOutcome(List.of(), null));
    }

    private static ToolInputs.Retrieve retrieve(String query) {
        return new ToolInputs.Retrieve(query, 5, "fused");
    }
}
