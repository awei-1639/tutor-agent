package com.tutor.agent.tool;

import com.tutor.contract.Intent;
import com.tutor.agent.expert.IntentRouter;
import com.tutor.agent.expert.RoutingPolicy;
import com.tutor.knowledge.retrieval.agentic.AgenticRetriever;
import com.tutor.knowledge.retrieval.fusion.FusedRetriever;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        RetrievalToolService service = service(false);

        service.retrieve(retrieve(QUERY), "trace-1");
        service.retrieve(retrieve(QUERY), "trace-2");

        verify(router, times(2)).routeDecision(anyString(), any(), anyString());
    }

    @Test
    void cacheEnabledRoutesSameQueryOnlyOnce() {
        stubHappyPath();
        RetrievalToolService service = service(true);

        service.retrieve(retrieve(QUERY), "trace-1");
        service.retrieve(retrieve(QUERY), "trace-2");

        verify(router, times(1)).routeDecision(anyString(), any(), anyString());
    }

    @Test
    void cacheEnabledStillRoutesDistinctQueries() {
        stubHappyPath();
        RetrievalToolService service = service(true);

        service.retrieve(retrieve(QUERY), "trace-1");
        service.retrieve(retrieve("前端工程师要会什么"), "trace-2");

        verify(router, times(2)).routeDecision(anyString(), any(), anyString());
    }

    private RetrievalToolService service(boolean cacheEnabled) {
        return new RetrievalToolService(retriever, agenticRetriever, router, routingPolicy, cacheEnabled);
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
