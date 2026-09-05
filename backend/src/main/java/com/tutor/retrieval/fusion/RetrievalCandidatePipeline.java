package com.tutor.retrieval.fusion;

import com.tutor.contract.Evidence;
import com.tutor.retrieval.GraphScope;
import com.tutor.retrieval.graph.GraphExpansionPolicy;
import com.tutor.retrieval.graph.GraphStore;
import com.tutor.retrieval.vector.VectorStore;
import com.tutor.llm.EmbeddingGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the dense/sparse/graph candidate pool before optional reranking. */
final class RetrievalCandidatePipeline {
    private static final Logger log = LoggerFactory.getLogger(RetrievalCandidatePipeline.class);

    private final EmbeddingGateway embeddings;
    private final VectorStore vectorStore;
    private final GraphStore graphStore;
    private final EntityAliasStore aliasStore;
    private final RetrievalProfile profile;

    RetrievalCandidatePipeline(EmbeddingGateway embeddings, VectorStore vectorStore,
                               GraphStore graphStore, EntityAliasStore aliasStore,
                               RetrievalProfile profile) {
        this.embeddings = embeddings;
        this.vectorStore = vectorStore;
        this.graphStore = graphStore;
        this.aliasStore = aliasStore;
        this.profile = profile;
    }

    record CandidateOutcome(List<Evidence> evidences, FusedRetriever.RetrievalTelemetry telemetry) {}

    List<Evidence> expandFrontier(List<Evidence> frontier, int topK,
                                  GraphExpansionPolicy policy, GraphScope scope) {
        if (frontier == null || frontier.isEmpty() || topK <= 0
                || policy == null || !policy.enabled()) return List.of();
        Map<String, Evidence> sourceById = new java.util.LinkedHashMap<>();
        frontier.stream()
                .filter(evidence -> evidence != null && evidence.nodeId() != null
                        && !evidence.nodeId().isBlank())
                .forEach(evidence -> sourceById.putIfAbsent(evidence.nodeId(), evidence));
        if (sourceById.isEmpty()) return List.of();

        GraphScope effectiveScope = scope == null ? GraphScope.publicOnly() : scope;
        List<GraphStore.Neighbor> neighbors = graphStore.expand(
                sourceById.keySet().stream().toList(), profile.expandPerSource(),
                Math.min(profile.expandLimit(), Math.max(topK * 4, topK)), policy, effectiveScope);
        if (neighbors.isEmpty()) return List.of();
        Map<String, VectorStore.VectorHit> hits = vectorStore.byNodeIds(
                neighbors.stream().map(GraphStore.Neighbor::dstId).distinct().toList(), effectiveScope);
        List<Evidence> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (GraphStore.Neighbor neighbor : neighbors) {
            if (!seen.add(neighbor.dstId())) continue;
            VectorStore.VectorHit hit = hits.get(neighbor.dstId());
            Evidence source = sourceById.get(neighbor.srcId());
            if (hit == null || source == null) continue;
            GraphExpansionPolicy.Rule rule = policy.ruleFor(neighbor.rel(), neighbor.direction());
            if (rule == null || !"active".equalsIgnoreCase(neighbor.status())) continue;
            double parentScore = Math.max(0D, Math.min(1D, source.score()));
            double score = parentScore * FusedRetriever.ALPHA * rule.weight()
                    * Math.max(0D, Math.min(1D, neighbor.confidence()));
            String path = source.graphPath() == null || source.graphPath().isBlank()
                    ? FusedRetriever.formatGraphPath(neighbor)
                    : source.graphPath() + " | " + FusedRetriever.formatGraphPath(neighbor);
            result.add(FusedRetriever.evidence(hit, score, path));
            if (result.size() >= topK) break;
        }
        return result;
    }

    CandidateOutcome retrieve(String query, int topK, String traceId, boolean fused,
                              GraphExpansionPolicy graphPolicy, GraphScope scope,
                              boolean channelFloorEnabled) {
        GraphScope effectiveScope = scope == null ? GraphScope.publicOnly() : scope;
        List<VectorStore.VectorHit> denseHits;
        try {
            float[] queryVector = embeddings.embedQuery(query, traceId);
            denseHits = vectorStore.search(queryVector, profile.vectorTopN(), effectiveScope);
        } catch (RuntimeException error) {
            log.warn("向量检索不可用, 降级稀疏检索 trace={} type={}",
                    traceId, error.getClass().getSimpleName());
            List<Evidence> fallback = sparseFallback(query, topK, effectiveScope);
            return new CandidateOutcome(fallback,
                    new FusedRetriever.RetrievalTelemetry(0, fallback.size(), 0, 0,
                            true, false, false, false));
        }
        if (!fused) {
            return new CandidateOutcome(denseHits.stream().limit(topK)
                    .map(hit -> FusedRetriever.evidence(hit, hit.score(), null)).toList(),
                    new FusedRetriever.RetrievalTelemetry(denseHits.size(), 0, 0, 0,
                            false, false, false, false));
        }

        List<VectorStore.VectorHit> sparseHits;
        boolean sparseDegraded = false;
        try {
            sparseHits = vectorStore.sparseSearch(query, profile.vectorTopN(),
                    profile.sparseThreshold(), effectiveScope);
        } catch (RuntimeException error) {
            log.warn("稀疏检索不可用, 保留向量结果 trace={} type={}",
                    traceId, error.getClass().getSimpleName());
            sparseHits = List.of();
            sparseDegraded = true;
        }

        List<String> aliasSources = aliasSources(query, graphPolicy, effectiveScope);
        List<String> expandSources = selectExpansionSources(denseHits, sparseHits,
                graphPolicy, aliasSources);
        List<GraphStore.Neighbor> neighbors = graphStore.expand(
                expandSources, profile.expandPerSource(), profile.expandLimit(),
                graphPolicy, effectiveScope);

        Set<String> resourceNodeIds = new LinkedHashSet<>();
        denseHits.stream().filter(FusedRetriever::isResourceHit)
                .map(VectorStore.VectorHit::nodeId).forEach(resourceNodeIds::add);
        sparseHits.stream().filter(FusedRetriever::isResourceHit)
                .map(VectorStore.VectorHit::nodeId).forEach(resourceNodeIds::add);
        Set<String> resourceOnlyIds = new LinkedHashSet<>();
        denseHits.stream().filter(FusedRetriever::isResourceOnlyHit)
                .map(VectorStore.VectorHit::nodeId).forEach(resourceOnlyIds::add);
        sparseHits.stream().filter(FusedRetriever::isResourceOnlyHit)
                .map(VectorStore.VectorHit::nodeId).forEach(resourceOnlyIds::add);

        Map<String, Double> fusedScores = RrfFusionPolicy.fuse(
                denseHits.stream().map(VectorStore.VectorHit::nodeId).toList(),
                sparseHits.stream().map(VectorStore.VectorHit::nodeId).toList(),
                neighbors, expandSources, FusedRetriever.isResourceSeeking(query),
                graphPolicy, resourceNodeIds, profile.rrfK(), profile.graphAlpha(),
                profile.sparseBeta(), profile.nonResourceDampen(), profile.resourceDampen(),
                resourceOnlyIds);

        Map<String, VectorStore.VectorHit> byId = new HashMap<>();
        denseHits.forEach(hit -> byId.put(hit.nodeId(), hit));
        sparseHits.forEach(hit -> byId.putIfAbsent(hit.nodeId(), hit));
        Map<String, String> graphPath = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (GraphStore.Neighbor neighbor : neighbors) {
            graphPath.putIfAbsent(neighbor.dstId(), FusedRetriever.formatGraphPath(neighbor));
            if (!byId.containsKey(neighbor.dstId())) missing.add(neighbor.dstId());
        }
        byId.putAll(vectorStore.byNodeIds(missing, effectiveScope));

        List<Evidence> ranked = fusedScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> {
                    VectorStore.VectorHit hit = byId.get(entry.getKey());
                    return hit == null ? null
                            : FusedRetriever.evidence(hit, entry.getValue(), graphPath.get(hit.nodeId()));
                })
                .filter(evidence -> evidence != null)
                .toList();
        List<Evidence> answerShaped = JobSkillAnswerPolicy.promoteRequiredSkills(
                ranked, neighbors, byId, query);
        List<Evidence> selected = !channelFloorEnabled ? answerShaped.stream().limit(topK).toList()
                : FusedRetriever.ensureChannelFloor(answerShaped, topK,
                denseHits.stream().map(VectorStore.VectorHit::nodeId).collect(java.util.stream.Collectors.toSet()),
                sparseHits.stream().map(VectorStore.VectorHit::nodeId).collect(java.util.stream.Collectors.toSet()),
                neighbors.stream().map(GraphStore.Neighbor::dstId).collect(java.util.stream.Collectors.toSet()));
        return new CandidateOutcome(selected, new FusedRetriever.RetrievalTelemetry(
                denseHits.size(), sparseHits.size(), neighbors.size(), expandSources.size(),
                false, sparseDegraded, false, false));
    }

    private List<String> aliasSources(String query, GraphExpansionPolicy graphPolicy,
                                      GraphScope scope) {
        if (graphPolicy == null || !graphPolicy.enabled()) return List.of();
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        if (aliasStore != null) {
            List<String> resolved = aliasStore.resolveNodeIds(FusedRetriever.seedAliases(query), 4);
            if (resolved != null) aliases.addAll(resolved);
        }
        List<String> graphAliases = graphStore.findSeedIds(FusedRetriever.seedAliases(query), 4, scope);
        if (graphAliases != null) aliases.addAll(graphAliases);
        return aliases.stream().limit(2).toList();
    }

    private List<String> selectExpansionSources(List<VectorStore.VectorHit> dense,
                                                List<VectorStore.VectorHit> sparse,
                                                GraphExpansionPolicy policy,
                                                List<String> aliases) {
        List<String> safeAliases = aliases == null ? List.of() : aliases.stream()
                .filter(id -> id != null && !id.isBlank()).distinct().limit(2).toList();
        int baseLimit = Math.max(0, profile.expandSourceN() - safeAliases.size());
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (dense != null) dense.stream()
                .filter(hit -> hit != null && hit.nodeId() != null
                        && hit.score() >= policy.minSourceScore())
                .limit(profile.denseExpandSourceN()).map(VectorStore.VectorHit::nodeId)
                .forEach(ids::add);
        if (sparse != null) sparse.stream()
                .filter(hit -> hit != null && hit.nodeId() != null
                        && hit.score() >= policy.minSourceScore())
                .limit(profile.sparseExpandSourceN()).map(VectorStore.VectorHit::nodeId)
                .forEach(ids::add);
        LinkedHashSet<String> result = new LinkedHashSet<>(ids.stream().limit(baseLimit).toList());
        result.addAll(safeAliases);
        return result.stream().limit(profile.expandSourceN()).toList();
    }

    private List<Evidence> sparseFallback(String query, int topK, GraphScope scope) {
        try {
            return vectorStore.sparseSearch(query, Math.max(topK, profile.vectorTopN()),
                            profile.sparseThreshold(), scope).stream().limit(topK)
                    .map(hit -> FusedRetriever.evidence(hit, hit.score(), null)).toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }
}
