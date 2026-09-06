package com.tutor.agent.tool;

import com.tutor.contract.Evidence;
import com.tutor.agent.expert.IntentRouter;
import com.tutor.agent.expert.RoutingPolicy;
import com.tutor.knowledge.retrieval.GraphScope;
import com.tutor.knowledge.retrieval.agentic.AgenticRetriever;
import com.tutor.knowledge.retrieval.fusion.FusedRetriever;
import com.tutor.knowledge.retrieval.graph.GraphExpansionPolicy;
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

    public RetrievalToolService(FusedRetriever retriever, AgenticRetriever agenticRetriever,
                                IntentRouter router, RoutingPolicy routingPolicy,
                                @Value("${tutor.retrieval.route-cache-enabled:false}") boolean routeCacheEnabled) {
        this.retriever = retriever;
        this.agenticRetriever = agenticRetriever;
        this.router = router;
        this.routingPolicy = routingPolicy;
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
        IntentRouter.RouteDecision decision = routeDecision(request.query(), traceId);
        RoutingPolicy.ExecutionPlan plan = routingPolicy.plan(decision, request.query());
        GraphExpansionPolicy policy = GraphExpansionPolicy.forFacets(plan.retrievalFacets(),
                "agentic".equals(mode) ? IntentRouter.RetrievalHint.MULTI_CANDIDATE : plan.retrievalHint());
        List<Evidence> results;
        if ("agentic".equals(mode)) {
            results = agenticRetriever.retrieveAdaptiveResult(request.query(), topK, traceId, true, policy,
                    GraphScope.publicOnly()).evidences();
        } else {
            results = retriever.retrieve(request.query(), topK, traceId, !"vector_only".equals(mode),
                    "fused_rerank".equals(mode), policy, GraphScope.publicOnly()).evidences();
        }
        return Map.of("mode", mode, "latency_ms", System.currentTimeMillis() - started,
                "results", results.stream().map(e -> Map.of("node_id", e.nodeId(), "type", e.nodeType(), "score", e.score())).toList());
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
