package com.tutor.conversation.chat.internal;

import com.tutor.expert.IntentRouter;
import com.tutor.expert.RoutingPolicy;
import com.tutor.contract.Intent;
import com.tutor.eval.InternalMemorySeedService;
import com.tutor.conversation.memory.application.FactRecallService;
import com.tutor.conversation.memory.application.LongTermMemoryService;
import com.tutor.tool.ToolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /internal/route 的回归：路由必须以本次请求的 traceId 计入 token 预算。
 * 曾经这里写死了字面量 "eval"，而 llm_turn_budget 以 trace_id 为主键且没有 TTL，
 * 于是所有历史评测的用量累加到同一行，越过 turn-token-limit 后路由永久降级，
 * 评测指标随之失真却没有任何报错。
 */
class InternalControllerRouteTraceTest {
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void routeChargesTheBudgetAgainstThisRequestsTraceId() {
        IntentRouter router = mock(IntentRouter.class);
        RoutingPolicy policy = mock(RoutingPolicy.class);
        when(router.routeDecision(anyString(), any(), anyString())).thenReturn(inScopeChat());
        when(policy.plan(any(), anyString())).thenReturn(chatPlan());
        MDC.put("traceId", "trace-per-request");

        new InternalController(router, policy, mock(ToolExecutor.class),
                mock(LongTermMemoryService.class), mock(FactRecallService.class),
                mock(InternalMemorySeedService.class))
                .route(new InternalController.RouteRequest("什么是 RAG"));

        verify(router).routeDecision(eq("什么是 RAG"), any(), eq("trace-per-request"));
    }

    private static IntentRouter.RouteDecision inScopeChat() {
        return new IntentRouter.RouteDecision(IntentRouter.Scope.IN_SCOPE, Intent.CHAT, List.of(), List.of(),
                IntentRouter.RetrievalHint.SINGLE, 0.9D, null, List.of(), false);
    }

    private static RoutingPolicy.ExecutionPlan chatPlan() {
        return new RoutingPolicy.ExecutionPlan(Intent.CHAT, List.of(), List.of(),
                IntentRouter.RetrievalHint.SINGLE, false, false, false, List.of());
    }
}
