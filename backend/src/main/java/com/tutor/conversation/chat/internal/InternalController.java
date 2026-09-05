package com.tutor.conversation.chat.internal;

import com.tutor.agent.expert.IntentRouter;
import com.tutor.agent.expert.RoutingPolicy;
import com.tutor.identity.auth.AuthContext;
import com.tutor.eval.InternalMemorySeedService;
import com.tutor.conversation.memory.application.FactRecallService;
import com.tutor.conversation.memory.application.LongTermMemoryService;
import com.tutor.conversation.memory.local.FactStore;
import com.tutor.agent.tool.ToolExecutionContext;
import com.tutor.agent.tool.ToolExecutor;
import com.tutor.agent.tool.ToolInputs;
import org.slf4j.MDC;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
    private final ToolExecutor toolExecutor;
    private final LongTermMemoryService longTermMemory;
    private final FactRecallService factRecall;
    private final InternalMemorySeedService memorySeedService;

    public InternalController(IntentRouter router,
                              RoutingPolicy routingPolicy,
                              ToolExecutor toolExecutor,
                              LongTermMemoryService longTermMemory,
                              FactRecallService factRecall,
                              InternalMemorySeedService memorySeedService) {
        this.router = router;
        this.routingPolicy = routingPolicy;
        this.toolExecutor = toolExecutor;
        this.longTermMemory = longTermMemory;
        this.factRecall = factRecall;
        this.memorySeedService = memorySeedService;
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



    public record RouteRequest(@NotBlank @Size(max = 4000) String question) {}

    /**
     * 记忆召回评估端点 (evals/run_memory_eval.mjs 使用)：复用与聊天完全相同的召回与排序管线。
     * userId 显式传入以便播种测试用户；/internal 仅存在于非生产环境。
     */
    public record MemoryRecallRequest(@NotNull Long userId,
                                      @NotBlank @Size(max = 4000) String query,
                                      @Min(1) @Max(20) Integer topK) {}

    @PostMapping("/memory-recall")
    public Map<String, Object> memoryRecall(@Valid @RequestBody MemoryRecallRequest req) {
        String traceId = MDC.get("traceId");
        int topK = req.topK() == null ? 5 : req.topK();
        LongTermMemoryService.RecallResult recall = longTermMemory.recall(req.userId(), req.query(), traceId);
        List<FactStore.UserFact> facts = factRecall.recall(req.userId(), req.query(), traceId);
        List<Map<String, Object>> episodeItems = recall.episodes().stream()
                .limit(topK)
                .map(episode -> Map.<String, Object>of(
                        "id", episode.id(),
                        "summary", episode.summary() == null ? "" : episode.summary(),
                        "relevance", episode.relevance()))
                .toList();
        List<Map<String, Object>> factItems = facts.stream()
                .limit(topK)
                .map(fact -> Map.<String, Object>of(
                        "id", fact.id(),
                        "fact_text", fact.factText(),
                        "category", fact.category(),
                        "confidence", fact.confidence()))
                .toList();
        return Map.of("episodes", episodeItems, "facts", factItems, "degraded", recall.degraded());
    }

    /** 记忆播种：为指定用户重建评估用的 episodes/facts。embedding 走真实网关，其余为固定文本。 */
    public record MemorySeedRequest(@NotNull Long userId,
                                    List<SeedEpisode> episodes,
                                    List<SeedFact> facts) {}

    public record SeedEpisode(String summary, List<String> topics, Integer ageDays) {}

    public record SeedFact(String text, String category, Double confidence, String status) {}

    @PostMapping("/memory-seed")
    public Map<String, Object> memorySeed(@Valid @RequestBody MemorySeedRequest req) {
        String traceId = MDC.get("traceId");
        List<InternalMemorySeedService.SeedEpisode> episodes = req.episodes() == null ? List.of()
                : req.episodes().stream().map(seed -> new InternalMemorySeedService.SeedEpisode(
                        seed.summary(), seed.topics(), seed.ageDays())).toList();
        List<InternalMemorySeedService.SeedFact> facts = req.facts() == null ? List.of()
                : req.facts().stream().map(seed -> new InternalMemorySeedService.SeedFact(
                        seed.text(), seed.category(), seed.confidence(), seed.status())).toList();
        InternalMemorySeedService.Result result = memorySeedService.seed(req.userId(), episodes, facts, traceId);
        return Map.of("user_id", result.userId(), "episodes", result.episodes(), "facts", result.facts());
    }

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
        // 必须用本次请求的 traceId，不能用固定字面量：llm_turn_budget 以 trace_id 为主键且
        // 没有 TTL，固定 key 会把所有历史评测的用量累加到同一行，越过 turn-token-limit 后
        // 每次路由都因"本轮 token 预算已用尽"降级成 CHAT+confidence=0，评测指标随之失真。
        String traceId = MDC.get("traceId");
        IntentRouter.RouteDecision decision = router.routeDecision(req.question(), List.of(), traceId);
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
