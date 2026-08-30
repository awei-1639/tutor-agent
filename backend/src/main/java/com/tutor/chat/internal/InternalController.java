package com.tutor.chat.internal;

import com.tutor.expert.IntentRouter;
import com.tutor.expert.RoutingPolicy;
import com.tutor.auth.AuthContext;
import com.tutor.llm.EmbeddingGateway;
import com.tutor.memory.application.FactRecallService;
import com.tutor.memory.application.LongTermMemoryService;
import com.tutor.memory.local.EpisodeStore;
import com.tutor.memory.local.FactStore;
import com.tutor.tool.ToolExecutionContext;
import com.tutor.tool.ToolExecutor;
import com.tutor.tool.ToolInputs;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final EpisodeStore episodeStore;
    private final FactStore factStore;
    private final EmbeddingGateway embeddingGateway;
    private final JdbcTemplate jdbc;

    public InternalController(IntentRouter router,
                              RoutingPolicy routingPolicy,
                              ToolExecutor toolExecutor,
                              LongTermMemoryService longTermMemory,
                              FactRecallService factRecall,
                              EpisodeStore episodeStore,
                              FactStore factStore,
                              EmbeddingGateway embeddingGateway,
                              JdbcTemplate jdbc) {
        this.router = router;
        this.routingPolicy = routingPolicy;
        this.toolExecutor = toolExecutor;
        this.longTermMemory = longTermMemory;
        this.factRecall = factRecall;
        this.episodeStore = episodeStore;
        this.factStore = factStore;
        this.embeddingGateway = embeddingGateway;
        this.jdbc = jdbc;
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
        jdbc.update("INSERT INTO users (id) VALUES (?) ON CONFLICT DO NOTHING", req.userId());
        // 幂等重建：先清掉该用户的派生记忆（不动 messages/conversations）。
        jdbc.update("DELETE FROM user_facts WHERE user_id=?", req.userId());
        jdbc.update("DELETE FROM episode_memory_tombstones WHERE user_id=?", req.userId());
        jdbc.update("DELETE FROM episodes WHERE user_id=?", req.userId());
        long conversationId = jdbc.queryForObject(
                "INSERT INTO conversations (user_id, last_active_at) VALUES (?, now()) RETURNING id",
                Long.class, req.userId());

        int episodeCount = 0;
        if (req.episodes() != null) {
            int index = 0;
            for (SeedEpisode seed : req.episodes()) {
                if (seed.summary() == null || seed.summary().isBlank()) continue;
                float[] embedding = embeddingGateway.embed(seed.summary(), traceId);
                // 每条种子 episode 用互异的负数源窗口，避免命中 (user, conv, from, to) 部分唯一索引。
                long window = -1000L - index;
                long id = episodeStore.insertIfAbsentReturningId(req.userId(), conversationId,
                        seed.summary(), seed.topics() == null ? List.of() : seed.topics(), List.of(),
                        embedding, window, window, 0L);
                index++;
                if (id == 0) continue;
                int ageDays = seed.ageDays() == null ? 0 : Math.max(0, seed.ageDays());
                jdbc.update("UPDATE episodes SET created_at = now() - (? * interval '1 day') WHERE id=?",
                        ageDays, id);
                episodeCount++;
            }
        }

        int factCount = 0;
        if (req.facts() != null) {
            for (SeedFact seed : req.facts()) {
                if (seed.text() == null || seed.text().isBlank()) continue;
                long id = factStore.insertIfAbsentReturningId(req.userId(), null, 0L,
                        seed.text(), seed.category(), seed.confidence() == null ? 0.7D : seed.confidence());
                if (id == 0) continue;
                if ("superseded".equalsIgnoreCase(seed.status())) {
                    jdbc.update("UPDATE user_facts SET status='superseded' WHERE id=?", id);
                }
                factCount++;
            }
        }
        return Map.of("user_id", req.userId(), "episodes", episodeCount, "facts", factCount);
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
