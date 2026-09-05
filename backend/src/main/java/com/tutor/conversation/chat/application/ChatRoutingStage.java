package com.tutor.conversation.chat.application;

import com.tutor.conversation.chat.application.ChatModels.RoutedTurn;
import com.tutor.conversation.chat.application.ChatModels.TurnContext;
import com.tutor.conversation.chat.support.TraceRecorder;
import com.tutor.conversation.context.ContextualQueryRewriter;
import com.tutor.conversation.context.ConversationContextSelector;
import com.tutor.expert.IntentRouter;
import com.tutor.expert.RoutingPolicy;
import com.tutor.llm.BudgetPressureService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Query rewrite and routing stage. It emits no terminal events and owns no persistence. */
final class ChatRoutingStage {
    private final ContextualQueryRewriter queryRewriter;
    private final IntentRouter router;
    private final RoutingPolicy routingPolicy;
    private final TraceRecorder trace;
    private volatile BudgetPressureService budgetPressure;

    ChatRoutingStage(ContextualQueryRewriter queryRewriter, IntentRouter router,
                     RoutingPolicy routingPolicy, TraceRecorder trace) {
        this.queryRewriter = queryRewriter;
        this.router = router;
        this.routingPolicy = routingPolicy;
        this.trace = trace;
    }

    void setBudgetPressure(BudgetPressureService budgetPressure) {
        this.budgetPressure = budgetPressure;
    }

    ContextualQueryRewriter.RewriteResult rewrite(String question, TurnContext context,
                                                  String traceId, ChatTurnEvents events) {
        events.onStage("rewriting");
        long rewriteStart = System.currentTimeMillis();
        ContextualQueryRewriter.RewriteResult rewritten =
                queryRewriter.rewrite(question, context.recentWindow(), traceId);
        trace.span(traceId, context.convId(), "query_rewrite", rewriteStart,
                rewritten.needsClarification(), Map.of(
                        "mode", rewritten.mode().name().toLowerCase(),
                        "rewritten", !Objects.equals(question, rewritten.standaloneQuery()),
                        "reference_count", rewritten.references().size(),
                        "needs_clarification", rewritten.needsClarification()));
        return rewritten;
    }

    RoutedTurn route(String executionQuestion, TurnContext context, String traceId,
                     ChatTurnEvents events) {
        events.onStage("routing");
        long start = System.currentTimeMillis();
        List<String> recentUser = ConversationContextSelector.routerContext(
                context.recentWindow(), executionQuestion);
        if (context.clarificationState().pending()) {
            recentUser = new ArrayList<>(recentUser);
            recentUser.add("系统提示：当前用户回复可能是在回答上一轮澄清问题，请优先结合该澄清上下文理解。");
        }
        IntentRouter.RouteDecision decision = router.routeDecision(executionQuestion, recentUser, traceId);
        if (decision == null) throw new IllegalStateException("路由决策不能为空");
        RoutingPolicy.ExecutionPlan plan = applyBudgetShedding(
                routingPolicy.plan(decision, executionQuestion));
        trace.span(traceId, context.convId(), "router", start, plan.degraded(), routingTrace(decision, plan));
        return new RoutedTurn(decision, plan);
    }

    private RoutingPolicy.ExecutionPlan applyBudgetShedding(RoutingPolicy.ExecutionPlan plan) {
        BudgetPressureService pressure = budgetPressure;
        if (pressure == null || pressure.level() == BudgetPressureService.Level.NORMAL) return plan;
        boolean multiHop = plan.allowMultiHopEscalation() && pressure.multiHopAllowed();
        List<String> reasons = new ArrayList<>(plan.reasonCodes());
        if (multiHop != plan.allowMultiHopEscalation()) reasons.add("BUDGET_MULTI_HOP_DISABLED");
        return new RoutingPolicy.ExecutionPlan(plan.intent(), plan.intents(), plan.retrievalFacets(),
                plan.retrievalHint(), multiHop, plan.skipRetrieval(), true, List.copyOf(reasons));
    }

    private Map<String, Object> routingTrace(IntentRouter.RouteDecision decision,
                                             RoutingPolicy.ExecutionPlan plan) {
        return Map.ofEntries(
                Map.entry("scope", decision.scope().name().toLowerCase()),
                Map.entry("intent", decision.intent().name().toLowerCase()),
                Map.entry("sub_intents", decision.subIntents().stream().map(Enum::name)
                        .map(String::toLowerCase).toList()),
                Map.entry("effective_intent", plan.intent().name().toLowerCase()),
                Map.entry("confidence", decision.confidence()),
                Map.entry("alternative_confidence", decision.alternativeConfidence() == null
                        ? "unavailable" : decision.alternativeConfidence()),
                Map.entry("calibrated_confidence", decision.calibratedConfidence() == null
                        ? "uncalibrated" : decision.calibratedConfidence()),
                Map.entry("retrieval_hint", decision.retrievalHint().name().toLowerCase()),
                Map.entry("skip_retrieval", plan.skipRetrieval()),
                Map.entry("allow_multi_hop", plan.allowMultiHopEscalation()),
                Map.entry("retrieval_facets", plan.retrievalFacets().stream().map(Enum::name)
                        .map(String::toLowerCase).toList()),
                Map.entry("degraded", plan.degraded()),
                Map.entry("budget_shed", plan.reasonCodes().stream()
                        .anyMatch(code -> code.startsWith("BUDGET_"))),
                Map.entry("reason_codes", plan.reasonCodes()));
    }
}
