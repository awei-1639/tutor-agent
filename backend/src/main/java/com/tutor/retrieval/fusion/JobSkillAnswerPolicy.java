package com.tutor.retrieval.fusion;

import com.tutor.contract.Evidence;
import com.tutor.retrieval.graph.GraphExpansionPolicy;
import com.tutor.retrieval.graph.GraphStore;
import com.tutor.retrieval.vector.VectorStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Badcase 10 的答案形态转换："某岗位要会什么技能"类查询的正确答案是岗位要求的
 * 技能集合, 但 RRF 融合会让岗位块自身与 202 个同类兄弟块把图扩展出的技能邻居全部
 * 挤出 topK。当查询为技能寻求型且融合排序首位是 job 节点时, 用该节点的
 * REQUIRES(出边)技能邻居替换它, 其余排序保持不变; 纯函数, 便于单测与评测归因。
 */
final class JobSkillAnswerPolicy {
    static final String RELATION = "REQUIRES";
    private static final int MAX_PROMOTED = 8;

    private JobSkillAnswerPolicy() { }

    static List<Evidence> promoteRequiredSkills(List<Evidence> ranked,
                                                List<GraphStore.Neighbor> neighbors,
                                                Map<String, VectorStore.VectorHit> hitsById,
                                                String query) {
        if (ranked == null || ranked.isEmpty()
                || neighbors == null || neighbors.isEmpty()
                || !JobSkillQueryClassifier.classify(query).seeking()) {
            return ranked;
        }
        Evidence top = ranked.get(0);
        if (top.nodeId() == null || !top.nodeId().startsWith("job:")) return ranked;

        Map<String, Evidence> promoted = new LinkedHashMap<>();
        neighbors.stream()
                .filter(neighbor -> top.nodeId().equals(neighbor.srcId()))
                .filter(neighbor -> RELATION.equals(neighbor.rel()))
                .filter(neighbor -> neighbor.direction() == GraphExpansionPolicy.Direction.OUTGOING)
                .filter(neighbor -> "active".equalsIgnoreCase(neighbor.status()))
                .sorted((a, b) -> Double.compare(b.confidence(), a.confidence()))
                .limit(MAX_PROMOTED)
                .forEach(neighbor -> {
                    VectorStore.VectorHit hit = hitsById == null ? null : hitsById.get(neighbor.dstId());
                    String text = hit != null ? hit.chunkText()
                            : (neighbor.dstName() == null ? "" : neighbor.dstName());
                    promoted.put(neighbor.dstId(), new Evidence(
                            neighbor.dstId(), neighbor.dstType(), text, top.score(),
                            FusedRetriever.formatGraphPath(neighbor),
                            hit == null ? null : hit.sourceUrl(),
                            hit == null ? top.sourceStatus() : hit.sourceStatus(),
                            hit == null ? null : hit.contentHash()));
                });
        if (promoted.isEmpty()) return ranked;

        List<Evidence> result = new ArrayList<>(promoted.values());
        for (int i = 1; i < ranked.size(); i++) result.add(ranked.get(i));
        return List.copyOf(result);
    }
}
