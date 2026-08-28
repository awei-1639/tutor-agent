package com.tutor.chat.internal;

import com.tutor.expert.IntentRouter;
import com.tutor.expert.RoutingPolicy;
import com.tutor.auth.AuthContext;
import com.tutor.tool.ToolExecutionContext;
import com.tutor.tool.ToolExecutor;
import com.tutor.tool.ToolInputs;
import org.slf4j.MDC;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 内部评估端点 (evals/run_eval.mjs 使用): 评估必须打真实管线, 禁止在脚本里重写检索逻辑。
 * 单机单用户开发环境, 不做鉴权 (Phase 4 多用户时随JWT一并处理)。
 */
@RestController
@RequestMapping("/internal")
public class InternalController {
    private final IntentRouter router;
    private final RoutingPolicy routingPolicy;

    public InternalController(IntentRouter router,
                              RoutingPolicy routingPolicy,
                              ToolExecutor toolExecutor) {
        this.router = router;
        this.routingPolicy = routingPolicy;
        this.toolExecutor = toolExecutor;
    }

    public record RetrieveRequest(@NotBlank @Size(max = 4000) String query,
                                  @Min(1) @Max(20) Integer topK, @Size(max = 32) String mode) {}

    @SuppressWarnings("unchecked")
    @PostMapping("/retrieve")
    public Map<String, Object> retrieve(@Valid @RequestBody RetrieveRequest req) {
        String traceId = MDC.get("traceId");
        return (Map<String, Object>) toolExecutor.execute("retrieve", new ToolInputs.Retrieve(req.query(), req.topK(), req.mode()),
                new ToolExecutionContext(traceId, "eval", AuthContext.requireUserId(), null, false));
    }

    private final ToolExecutor toolExecutor;

    public record RouteRequest(@NotBlank @Size(max = 4000) String question) {}

    @SuppressWarnings("unchecked")
    @PostMapping("/push-run")
    public Map<String, Object> pushRun(@org.springframework.web.bind.annotation.RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        String traceId = MDC.get("traceId");
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? traceId : idempotencyKey;
        return (Map<String, Object>) toolExecutor.execute("push_run", new ToolInputs.Empty(),
                new ToolExecutionContext(traceId, "scheduler", AuthContext.requireUserId(), key, true));
    }

    @PostMapping("/route")
    public Map<String, Object> route(@Valid @RequestBody RouteRequest req) {
        IntentRouter.RouteDecision decision = router.routeDecision(req.question(), List.of(), "eval");
        RoutingPolicy.ExecutionPlan plan = routingPolicy.plan(decision, req.question());
        return Map.ofEntries(
                Map.entry("intent", decision.intent().name().toLowerCase()),
                Map.entry("sub_intents", decision.subIntents().stream().map(Enum::name).map(String::toLowerCase).toList()),
                Map.entry("effective_intent", plan.intent().name().toLowerCase()),
                Map.entry("retrieval_facets", plan.retrievalFacets().stream()
                        .map(Enum::name).map(String::toLowerCase).toList()),
                Map.entry("scope", decision.scope().name().toLowerCase()),
                Map.entry("retrieval_hint", decision.retrievalHint().name().toLowerCase()),
                Map.entry("skip_retrieval", plan.skipRetrieval()),
                Map.entry("allow_multi_hop", plan.allowMultiHopEscalation()),
                Map.entry("confidence", decision.confidence()),
                Map.entry("calibrated_confidence", decision.calibratedConfidence() == null
                        ? "uncalibrated" : decision.calibratedConfidence()),
                Map.entry("reason_codes", decision.reasonCodes()),
                Map.entry("degraded", decision.degraded() || plan.degraded()));
    }
}
