package com.tutor.agent.tool;

import com.tutor.contract.Evidence;
import com.tutor.agent.expert.IntentRouter;
import com.tutor.agent.expert.RetrievalFacetRules;
import com.tutor.agent.expert.RoutingPolicy;
import com.tutor.knowledge.retrieval.GraphScope;
import com.tutor.knowledge.retrieval.agentic.AgenticRetriever;
import com.tutor.knowledge.retrieval.fusion.FusedRetriever;
import com.tutor.knowledge.retrieval.graph.GraphExpansionPolicy;
import com.tutor.knowledge.retrieval.personalize.ProfilePersonalizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RetrievalToolService {
    private static final int CACHE_MAX_ENTRIES = 1024;

    private final FusedRetriever retriever;
    private final AgenticRetriever agenticRetriever;
    private final IntentRouter router;
    private final RoutingPolicy routingPolicy;
    /**
     * 路由决策按 query 文本缓存。决策对空对话上下文是确定性的 (temperature=0),
     * 而检索评测的三种模式会对同一测集各打一遍 /internal/retrieve, 每次都触发
     * 一次路由 LLM——这是评测 token 消耗的大头。进程内有界 LRU, 默认关闭,
     * 评测脚本经 TUTOR_RETRIEVAL_ROUTE_CACHE_ENABLED 开启, 生产行为不变。
     */
    private final Map<String, IntentRouter.RouteDecision> routeCache;

    /**
     * 工具侧只需要检索 facet —— 意图、澄清、越界都由主流程路由决定过了。为每条子查询
     * 再付一次完整路由调用是纯浪费 (一轮对话里工具检索可能触发多次)。默认走本地规则
     * 判定 facet, 零 LLM 调用; 关掉即回到"每次检索先路由"的旧行为。
     */
    private final boolean facetRulesEnabled;

    public RetrievalToolService(FusedRetriever retriever, AgenticRetriever agenticRetriever,
                                IntentRouter router, RoutingPolicy routingPolicy,
                                @Value("${tutor.retrieval.route-cache-enabled:false}") boolean routeCacheEnabled,
                                @Value("${tutor.retrieval.facet-rules-enabled:true}") boolean facetRulesEnabled) {
        this.retriever = retriever;
        this.agenticRetriever = agenticRetriever;
        this.router = router;
        this.routingPolicy = routingPolicy;
        this.facetRulesEnabled = facetRulesEnabled;
        this.routeCache = routeCacheEnabled
                ? new LinkedHashMap<>(64, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, IntentRouter.RouteDecision> eldest) {
                        return size() > CACHE_MAX_ENTRIES;
                    }
                }
                : null;
    }

    public Map<String, Object> retrieve(ToolInputs.Retrieve request, String traceId) {
        String mode = request.mode() == null ? "agentic" : request.mode();
        long started = System.currentTimeMillis();
        int topK = request.topK() == null ? 5 : request.topK();
        List<RoutingPolicy.RetrievalFacet> facets;
        IntentRouter.RetrievalHint hint;
        if (facetRulesEnabled) {
            facets = RetrievalFacetRules.classify(request.query());
            // 规则不产出 hint; 单跳是保守默认, 且 forFacets 只在 hint==NONE 时才取消扩展。
            hint = IntentRouter.RetrievalHint.SINGLE;
        } else {
            RoutingPolicy.ExecutionPlan plan = routingPolicy.plan(routeDecision(request.query(), traceId),
                    request.query());
            facets = plan.retrievalFacets();
            hint = plan.retrievalHint();
        }
        GraphExpansionPolicy policy = GraphExpansionPolicy.forFacets(facets,
                "agentic".equals(mode) || "agentic_personalized".equals(mode)
                        ? IntentRouter.RetrievalHint.MULTI_CANDIDATE : hint);
        boolean personalized = "fused_personalized".equals(mode) || "agentic_personalized".equals(mode);
        List<Evidence> results;
        if ("agentic".equals(mode) || "agentic_personalized".equals(mode)) {
            List<Evidence> pool = agenticRetriever.retrieveAdaptiveResult(request.query(),
                    personalized ? topK + ProfilePersonalizer.POOL_EXTRA : topK,
                    traceId, true, policy, GraphScope.publicOnly()).evidences();
            results = personalized ? personalize(request.profile(), pool, topK) : pool;
        } else if (personalized) {
            List<Evidence> pool = retriever.retrieve(request.query(),
                    topK + ProfilePersonalizer.POOL_EXTRA, traceId, true, false, policy,
                    GraphScope.publicOnly()).evidences();
            results = personalize(request.profile(), pool, topK);
        } else {
            results = retriever.retrieve(request.query(), topK, traceId, !"vector_only".equals(mode),
                    "fused_rerank".equals(mode), policy, GraphScope.publicOnly()).evidences();
        }
        return Map.of("mode", mode, "latency_ms", System.currentTimeMillis() - started,
                "results", results.stream().map(e -> Map.of("node_id", e.nodeId(), "type", e.nodeType(), "score", e.score())).toList());
    }

    /** 画像重排失败不阻断检索: 信号缺失时等价于纯裁剪, 解析异常降级为无画像。 */
    private static List<Evidence> personalize(Map<String, Object> profile, List<Evidence> pool, int topK) {
        ProfilePersonalizer.Signal signal;
        try {
            signal = ProfilePersonalizer.fromProfile(profile);
        } catch (RuntimeException degraded) {
            signal = ProfilePersonalizer.fromProfile(null);
        }
        return ProfilePersonalizer.personalize(signal, pool, topK).evidences();
    }

    private IntentRouter.RouteDecision routeDecision(String query, String traceId) {
        if (routeCache == null) return router.routeDecision(query, List.of(), traceId);
        synchronized (routeCache) {
            IntentRouter.RouteDecision cached = routeCache.get(query);
            if (cached != null) return cached;
        }
        IntentRouter.RouteDecision decision = router.routeDecision(query, List.of(), traceId);
        synchronized (routeCache) {
            routeCache.put(query, decision);
        }
        return decision;
    }
}
