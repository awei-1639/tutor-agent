package com.tutor.agent.tool;

import com.tutor.contract.Evidence;
import com.tutor.agent.expert.IntentRouter;
import com.tutor.agent.expert.RoutingPolicy;
import com.tutor.knowledge.retrieval.GraphScope;
import com.tutor.knowledge.retrieval.agentic.AgenticRetriever;
import com.tutor.knowledge.retrieval.fusion.FusedRetriever;
import com.tutor.knowledge.retrieval.graph.GraphExpansionPolicy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class RetrievalToolService {
    private final FusedRetriever retriever;
    private final AgenticRetriever agenticRetriever;
    private final IntentRouter router;
    private final RoutingPolicy routingPolicy;

    public RetrievalToolService(FusedRetriever retriever, AgenticRetriever agenticRetriever,
                                IntentRouter router, RoutingPolicy routingPolicy) {
        this.retriever = retriever;
        this.agenticRetriever = agenticRetriever;
        this.router = router;
        this.routingPolicy = routingPolicy;
    }

    public Map<String, Object> retrieve(ToolInputs.Retrieve request, String traceId) {
        String mode = request.mode() == null ? "agentic" : request.mode();
        long started = System.currentTimeMillis();
        int topK = request.topK() == null ? 5 : request.topK();
        IntentRouter.RouteDecision decision = router.routeDecision(request.query(), List.of(), traceId);
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
}
