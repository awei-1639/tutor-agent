package com.tutor.knowledge.retrieval.fusion;

import com.tutor.knowledge.retrieval.graph.GraphExpansionPolicy;
import com.tutor.knowledge.retrieval.graph.GraphStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 三路 RRF 融合策略：稠密、稀疏和图扩展贡献均采用有界最大值抑制。 */
final class RrfFusionPolicy {
    private RrfFusionPolicy() {
    }

    /**
     * @param sources 扩展源, 按 RRF rank 顺序排列且元素唯一(由 {@code LinkedHashSet} 去重保证)。
     *                重复源以先出现者的更优 rank 为准(putIfAbsent)。
     */
    static Map<String, Double> fuse(List<String> dense, List<String> sparse, List<GraphStore.Neighbor> neighbors,
                                    List<String> sources, boolean resourceSeeking, GraphExpansionPolicy policy,
                                    Set<String> resourceIds, int rrfK, double alpha, double beta, double dampen,
                                    double resourceDampen, Set<String> resourceOnlyIds) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < dense.size(); i++) {
            double value = 1D / (rrfK + i + 1);
            if (!resourceSeeking) value *= channelDampen(resourceOnly(resourceOnlyIds, dense.get(i)), resourceDampen);
            scores.merge(dense.get(i), value, Double::sum);
        }
        // LinkedHashMap: 稀疏同分 id 的取最优顺序须固定, 否则 HashMap 桶序让输出随 JVM 漂移。
        Map<String, Double> sparseScores = new LinkedHashMap<>();
        for (int i = 0; i < sparse.size(); i++) {
            String id = sparse.get(i); double value = beta / (rrfK + i + 1);
            if (resourceSeeking && !resource(id, resourceIds)) value *= dampen;
            if (!resourceSeeking) value *= channelDampen(resourceOnly(resourceOnlyIds, id), resourceDampen);
            sparseScores.merge(id, value, Double::max);
        }
        sparseScores.forEach((id, value) -> scores.merge(id, value, Double::sum));
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (int i = 0; i < sources.size(); i++) ranks.putIfAbsent(sources.get(i), i + 1);
        Map<String, Double> expanded = new LinkedHashMap<>();
        for (GraphStore.Neighbor neighbor : neighbors) {
            GraphExpansionPolicy.Rule rule = policy == null ? null : policy.ruleFor(neighbor.rel(), neighbor.direction());
            if (rule == null || !"active".equalsIgnoreCase(neighbor.status())) continue;
            // 拒绝非有限置信度: NaN 会穿透 Math.max/min 污染得分。
            if (!Double.isFinite(neighbor.confidence())) continue;
            int rank = ranks.getOrDefault(neighbor.srcId(), sources.size() + 1);
            double value = alpha * rule.weight() * Math.max(0D, Math.min(1D, neighbor.confidence())) / (rrfK + rank);
            if (resourceSeeking && !resource(neighbor.dstId(), resourceIds)) value *= dampen;
            if (!resourceSeeking) value *= channelDampen(resourceOnly(resourceOnlyIds, neighbor.dstId()), resourceDampen);
            expanded.merge(neighbor.dstId(), value, Double::max);
        }
        expanded.forEach((id, value) -> scores.merge(id, value, Double::sum));
        return scores;
    }

    /**
     * 对称通道保护: 资源型查询已由 dampen 降权非资源节点; 反方向(技能/岗位型查询被课程清单
     * 挤位, q003 坏例)由 resourceDampen 覆盖。判据是 resourceOnly——仅种子资源(res:)与
     * resource_kind='resource' 的文档; 普通知识文档(doc:)是概念问题的正主, 不降权。
     * 默认 1.0 = 不启用, 经离线评测确认收益后再收紧。
     */
    private static double channelDampen(boolean isResourceOnlyNode, double resourceDampen) {
        return isResourceOnlyNode ? resourceDampen : 1D;
    }

    private static boolean resourceOnly(Set<String> resourceOnlyIds, String id) {
        return (id != null && id.startsWith("res:")) || (resourceOnlyIds != null && resourceOnlyIds.contains(id));
    }

    private static boolean resource(String id, Set<String> ids) {
        return (ids != null && ids.contains(id)) || (id != null && (id.startsWith("res:") || id.startsWith("doc:") || id.startsWith("document:")));
    }
}
