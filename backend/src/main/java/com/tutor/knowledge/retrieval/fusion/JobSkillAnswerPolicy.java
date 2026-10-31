package com.tutor.knowledge.retrieval.fusion;

import com.tutor.contract.Evidence;
import com.tutor.knowledge.retrieval.graph.GraphExpansionPolicy;
import com.tutor.knowledge.retrieval.graph.GraphStore;
import com.tutor.knowledge.retrieval.vector.VectorStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 「某岗位要会什么技能」类查询的答案形态转换。
 *
 * <p>这类查询的正确答案是岗位要求的技能集合，但岗位节点自身也是图谱里的独立切片，
 * 语义上与问题高度相似，因此在 RRF 里稳定占据 topK 名额。全量评测（280 条）显示：
 * gold 只由 skill/resource 两类节点构成，**没有任何一条 gold 是岗位节点**，而 job 切片
 * 的 300 个 top5 槽位里有 85 个（28.3%）被岗位节点占掉——这不是排序调参能解决的，
 * 是通道语义问题：岗位节点是需求侧的索引，不是可作答的内容。
 *
 * <p>修复方式：把命中的岗位节点替换成它 REQUIRES 的技能，并把其余岗位节点一并移出答案位。
 * 三个约束防止它变成对评测集的过拟合：
 * <ol>
 *   <li>只在查询被判定为技能寻求型时触发（岗位介绍/职位推荐类查询不受影响）；</li>
 *   <li>提升数量上限 {@code min(6, topK-1)}——始终给独立召回留一个名额，
 *       避免一个认错的岗位节点把整个答案吞掉（实测 topK-1 还让 Hit 从 96.4% 回到 97.5%）；</li>
 *   <li>已在 topK 内的技能不重复提升，保持原有的多通道证据排序。</li>
 * </ol>
 * 纯函数，便于单测与评测归因。
 */
final class JobSkillAnswerPolicy {
    static final String RELATION = "REQUIRES";
    static final String JOB_PREFIX = "job:";
    /** 硬上限: 一个岗位的 REQUIRES 通常是 4-6 条, 超过 6 条说明图数据异常, 不该全部塞进答案位。 */
    static final int MAX_PROMOTED = 6;

    private JobSkillAnswerPolicy() { }

    static List<Evidence> promoteRequiredSkills(List<Evidence> ranked,
                                                List<GraphStore.Neighbor> requiresEdges,
                                                Map<String, VectorStore.VectorHit> hitsById,
                                                String query,
                                                int topK) {
        if (ranked == null || ranked.isEmpty() || topK <= 0
                || requiresEdges == null || requiresEdges.isEmpty()
                || !JobSkillQueryClassifier.classify(query).seeking()) {
            return ranked;
        }
        // 取排序最靠前的岗位节点作为「这份需求单」的代表; 其余岗位节点一律让位。
        Evidence job = ranked.stream()
                .filter(evidence -> evidence.nodeId() != null && evidence.nodeId().startsWith(JOB_PREFIX))
                .findFirst().orElse(null);
        if (job == null) return ranked;

        int budget = Math.max(1, Math.min(MAX_PROMOTED, topK - 1));
        Set<String> alreadyRanked = ranked.stream().limit(topK)
                .map(Evidence::nodeId).filter(id -> id != null)
                .collect(Collectors.toSet());
        List<GraphStore.Neighbor> edges = requiresEdges.stream()
                .filter(neighbor -> neighbor != null && job.nodeId().equals(neighbor.srcId()))
                .filter(neighbor -> RELATION.equals(neighbor.rel()))
                .filter(neighbor -> neighbor.direction() == GraphExpansionPolicy.Direction.OUTGOING)
                .filter(neighbor -> "active".equalsIgnoreCase(neighbor.status()))
                .filter(neighbor -> neighbor.dstId() != null && !neighbor.dstId().startsWith(JOB_PREFIX))
                .sorted(Comparator.comparingDouble(GraphStore.Neighbor::confidence).reversed()
                        .thenComparing(GraphStore.Neighbor::dstId))
                .toList();

        List<Evidence> promoted = new ArrayList<>(budget);
        for (GraphStore.Neighbor neighbor : edges) {
            if (promoted.size() >= budget) break;
            if (alreadyRanked.contains(neighbor.dstId())) continue;
            VectorStore.VectorHit hit = hitsById == null ? null : hitsById.get(neighbor.dstId());
            String text = hit != null ? hit.chunkText()
                    : (neighbor.dstName() == null ? "" : neighbor.dstName());
            promoted.add(new Evidence(neighbor.dstId(), neighbor.dstType(), text, job.score(),
                    FusedRetriever.formatGraphPath(neighbor),
                    hit == null ? null : hit.sourceUrl(),
                    hit == null ? job.sourceStatus() : hit.sourceStatus(),
                    hit == null ? null : hit.contentHash(),
                    hit != null && hit.ownerSelf() ? Evidence.KIND_SELF
                            // 无命中回捞时无法判定归属; 该提升路径挂在公开种子岗位节点下,
                            // 邻居默认按参考资料处理, 宁可漏标不可冒标用户材料。
                            : Evidence.KIND_REFERENCE));
        }
        if (promoted.isEmpty()) return ranked;

        Set<String> promotedIds = promoted.stream().map(Evidence::nodeId).collect(Collectors.toSet());
        List<Evidence> rest = ranked.stream()
                .filter(evidence -> evidence.nodeId() != null)
                .filter(evidence -> !evidence.nodeId().startsWith(JOB_PREFIX))
                .filter(evidence -> !promotedIds.contains(evidence.nodeId()))
                .toList();
        List<Evidence> result = new ArrayList<>(promoted.size() + rest.size());
        result.addAll(promoted);
        result.addAll(rest);
        return List.copyOf(result);
    }
}
