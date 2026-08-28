package com.tutor.retrieval.fusion;

import com.tutor.retrieval.graph.GraphExpansionPolicy;
import com.tutor.retrieval.graph.GraphStore;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 三路 RRF 融合策略：稠密、稀疏和图扩展贡献均采用有界最大值抑制。 */
final class RrfFusionPolicy {
    static Map<String, Double> fuse(List<String> dense, List<String> sparse, List<GraphStore.Neighbor> neighbors,
                                    List<String> sources, boolean resourceSeeking, GraphExpansionPolicy policy,
                                    Set<String> resourceIds, int rrfK, double alpha, double beta, double dampen) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < dense.size(); i++) scores.merge(dense.get(i), 1D / (rrfK + i + 1), Double::sum);
        Map<String, Double> sparseScores = new HashMap<>();
        for (int i = 0; i < sparse.size(); i++) {
            String id = sparse.get(i); double value = beta / (rrfK + i + 1);
            if (resourceSeeking && !resource(id, resourceIds)) value *= dampen;
            sparseScores.merge(id, value, Double::max);
        }
        sparseScores.forEach((id, value) -> scores.merge(id, value, Double::sum));
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < sources.size(); i++) ranks.put(sources.get(i), i + 1);
        Map<String, Double> expanded = new HashMap<>();
        for (GraphStore.Neighbor neighbor : neighbors) {
            GraphExpansionPolicy.Rule rule = policy == null ? null : policy.ruleFor(neighbor.rel(), neighbor.direction());
            if (rule == null || !"active".equalsIgnoreCase(neighbor.status())) continue;
            int rank = ranks.getOrDefault(neighbor.srcId(), sources.size() + 1);
            double value = alpha * rule.weight() * Math.max(0D, Math.min(1D, neighbor.confidence())) / (rrfK + rank);
            if (resourceSeeking && !resource(neighbor.dstId(), resourceIds)) value *= dampen;
            expanded.merge(neighbor.dstId(), value, Double::max);
        }
        expanded.forEach((id, value) -> scores.merge(id, value, Double::sum));
        return scores;
    }

    private static boolean resource(String id, Set<String> ids) {
        return (ids != null && ids.contains(id)) || (id != null && (id.startsWith("res:") || id.startsWith("doc:") || id.startsWith("document:")));
    }
}
