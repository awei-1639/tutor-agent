package com.tutor.chat.application;

import com.tutor.identity.auth.AuthContext;
import com.tutor.chat.application.ChatModels.RetrievedContext;
import com.tutor.chat.application.ChatModels.TurnContext;
import com.tutor.chat.support.TraceRecorder;
import com.tutor.contract.Evidence;
import com.tutor.expert.RoutingPolicy;
import com.tutor.memory.application.FactRecallService;
import com.tutor.memory.application.LongTermMemoryService;
import com.tutor.memory.local.EpisodeStore;
import com.tutor.memory.local.FactStore;
import com.tutor.retrieval.GraphScope;
import com.tutor.retrieval.agentic.AgenticRetriever;
import com.tutor.retrieval.graph.GraphExpansionPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Memory and knowledge retrieval stage. It emits evidence/memory events but owns no persistence. */
final class ChatRetrievalStage {
    private static final int TOP_K = 5;

    private final AgenticRetriever agenticRetriever;
    private final LongTermMemoryService longTermMemory;
    private final FactRecallService factRecall;
    private final TraceRecorder trace;

    ChatRetrievalStage(AgenticRetriever agenticRetriever, LongTermMemoryService longTermMemory,
                       FactRecallService factRecall, TraceRecorder trace) {
        this.agenticRetriever = agenticRetriever;
        this.longTermMemory = longTermMemory;
        this.factRecall = factRecall;
        this.trace = trace;
    }

    RetrievedContext retrieve(String executionQuestion, TurnContext context,
                              RoutingPolicy.ExecutionPlan plan, String traceId,
                              ChatTurnEvents events) {
        if (plan.skipRetrieval()) {
            trace.span(traceId, context.convId(), "retrieve", System.currentTimeMillis(), false,
                    Map.of("skipped", true, "intent", plan.intent().name()));
            return new RetrievedContext(List.of(), List.of(), List.of());
        }

        long memoryStart = System.currentTimeMillis();
        LongTermMemoryService.RecallResult memoryRecall =
                longTermMemory.recall(context.userId(), executionQuestion, traceId);
        trace.span(traceId, context.convId(), "memory_recall", memoryStart, memoryRecall.degraded());

        long factStart = System.currentTimeMillis();
        List<FactStore.UserFact> facts = factRecall.recall(
                context.userId(), executionQuestion, traceId);
        trace.span(traceId, context.convId(), "facts_recall", factStart, false,
                Map.of("fact_count", facts.size()));

        List<ChatModels.MemoryRef> memoryRefs = new ArrayList<>();
        for (EpisodeStore.Episode episode : memoryRecall.episodes()) {
            memoryRefs.add(new ChatModels.MemoryRef("episode", episode.id(), episode.summary()));
        }
        for (FactStore.UserFact fact : facts) {
            memoryRefs.add(new ChatModels.MemoryRef("fact", fact.id(), fact.factText()));
        }
        events.onMemories(memoryRefs);

        events.onStage("retrieving");
        long start = System.currentTimeMillis();
        GraphExpansionPolicy graphPolicy = GraphExpansionPolicy.forFacets(
                plan.retrievalFacets(), plan.retrievalHint());
        AgenticRetriever.RetrievalResult result = agenticRetriever.retrieveAdaptiveResult(
                executionQuestion, TOP_K, traceId, plan.allowMultiHopEscalation(), graphPolicy,
                GraphScope.forUser(context.userId(), AuthContext.currentTenantId()));
        if (result == null) throw new IllegalStateException("检索结果不能为空");

        List<Evidence> evidences = result.evidences();
        trace.span(traceId, context.convId(), "retrieve", start, false,
                retrievalTrace(plan, graphPolicy, result, evidences));
        events.onCitations(evidences);
        return new RetrievedContext(evidences, memoryRecall.episodes(), facts);
    }

    private Map<String, Object> retrievalTrace(RoutingPolicy.ExecutionPlan executionPlan,
                                               GraphExpansionPolicy graphPolicy,
                                               AgenticRetriever.RetrievalResult result,
                                               List<Evidence> evidences) {
        return Map.ofEntries(
                Map.entry("requested_mode", executionPlan.allowMultiHopEscalation()
                        ? "multi_candidate" : "single"),
                Map.entry("multi_hop_candidate", result.multiHopCandidate()),
                Map.entry("hops", result.hops()),
                Map.entry("stop_reason", result.stopReason()),
                Map.entry("evidence_count", evidences.size()),
                Map.entry("graph_relations", graphPolicy.relationDescriptions()),
                Map.entry("graph_policy", graphPolicy.policyDescriptions()),
                Map.entry("resource_facet", executionPlan.retrievalFacets().contains(
                        RoutingPolicy.RetrievalFacet.RESOURCE)),
                Map.entry("retrieval_profile_version", agenticRetriever.retrievalProfileVersion()),
                Map.entry("dense_candidate_count", result.telemetry().denseCandidates()),
                Map.entry("sparse_candidate_count", result.telemetry().sparseCandidates()),
                Map.entry("graph_candidate_count", result.telemetry().graphCandidates()),
                Map.entry("graph_expansion_source_count", result.telemetry().graphExpansionSources()),
                Map.entry("embedding_degraded", result.telemetry().embeddingDegraded()),
                Map.entry("sparse_degraded", result.telemetry().sparseDegraded()),
                Map.entry("rerank_applied", result.telemetry().rerankApplied()),
                Map.entry("rerank_degraded", result.telemetry().rerankDegraded()),
                Map.entry("final_graph_evidence_count", evidences.stream()
                        .filter(evidence -> evidence.graphPath() != null
                                && !evidence.graphPath().isBlank()).count()),
                Map.entry("final_direct_evidence_count", evidences.stream()
                        .filter(evidence -> evidence.graphPath() == null
                                || evidence.graphPath().isBlank()).count()),
                Map.entry("graph_scope", AuthContext.currentTenantId() == null
                        ? "user+public" : "user+tenant+public"));
    }
}
