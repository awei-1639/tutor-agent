package com.tutor.retrieval;

import com.tutor.contract.Evidence;
import com.tutor.llm.LlmGateway;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 融合检索 (实现设计 4.1 管线): 向量top20 → 图谱白名单一跳扩展(衰减α=0.7, 限量30) → RRF融合 → topK。
 * RRF: score = Σ 1/(K + rank); 扩展节点得分乘 α。
 */
@Component
public class FusedRetriever {
    /** α>0.733才能让rank1源的扩展节点(α/(K+1))超过向量第5名(1/(K+5))——否则图谱通道无法触及top5 */
    static final double ALPHA = 0.85;
    /**
     * RRF K=10 (非常规60): K=60会把分差拉平, 纯扩展节点数学上永远低于向量第20名,
     * 图谱通道形同虚设; K=10下rank1源的扩展分(α/11≈0.064)可与向量rank5-8竞争——评估调参结论。
     */
    static final int RRF_K = 10;
    static final int VECTOR_TOPN = 20;
    static final int EXPAND_SOURCE_N = 10;  // 只对向量前10名做扩展
    static final int EXPAND_PER_SOURCE = 6; // 每源配额, 防高扇出边挤爆名额
    static final int EXPAND_LIMIT = 40;

    private final LlmGateway gateway;
    private final VectorStore vectorStore;
    private final GraphStore graphStore;

    public FusedRetriever(LlmGateway gateway, VectorStore vectorStore, GraphStore graphStore) {
        this.gateway = gateway;
        this.vectorStore = vectorStore;
        this.graphStore = graphStore;
    }

    static final int RERANK_POOL = 12; // 融合后进入重排的候选数

    public List<Evidence> retrieve(String query, int topK, String traceId) {
        return retrieve(query, topK, traceId, true, true); // 生产路径: 融合+重排
    }

    /**
     * 评估三模式: vector_only / fused / fused_rerank(查询自适应)。
     * 重排只用于"找资源"类查询——三方评估结论: reranker使resource_rec 66.7→86.9%(反超向量基线),
     * 但会降权图扩展节点(job切片35→11.7%), 因为技能chunk文本"看不出"在回答岗位问题而它们正是gold。
     * 选择规则仅依赖查询文本, 不依赖标签。重排失败降级保持融合排序 (降级矩阵)。
     */
    public List<Evidence> retrieve(String query, int topK, String traceId, boolean fused, boolean rerank) {
        boolean rerankEffective = rerank && isResourceSeeking(query);
        List<Evidence> candidates = retrieveFused(query, rerankEffective ? Math.max(topK, RERANK_POOL) : topK, traceId, fused);
        if (!rerankEffective || candidates.size() <= topK) {
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }
        try {
            double[] scores = gateway.rerank(query, candidates.stream().map(Evidence::chunkText).toList(), traceId);
            return java.util.stream.IntStream.range(0, candidates.size()).boxed()
                    .sorted((a, b) -> Double.compare(scores[b], scores[a]))
                    .limit(topK)
                    .map(i -> {
                        Evidence e = candidates.get(i);
                        return new Evidence(e.nodeId(), e.nodeType(), e.chunkText(), scores[i], e.graphPath());
                    })
                    .toList();
        } catch (RuntimeException ex) {
            // 降级: 重排不可用不阻断检索
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }
    }

    private List<Evidence> retrieveFused(String query, int topK, String traceId, boolean fused) {
        float[] qv = gateway.embed(query, traceId);
        List<VectorStore.VectorHit> vecHits = vectorStore.search(qv, VECTOR_TOPN);
        if (!fused) {
            return vecHits.stream().limit(topK)
                    .map(h -> new Evidence(h.nodeId(), h.nodeType(), h.chunkText(), h.score(), null))
                    .toList();
        }

        List<String> expandSources = vecHits.stream().limit(EXPAND_SOURCE_N)
                .map(VectorStore.VectorHit::nodeId).toList();
        List<GraphStore.Neighbor> neighbors = graphStore.expand(expandSources, EXPAND_PER_SOURCE, EXPAND_LIMIT);

        Map<String, Double> fusedScores = fuse(
                vecHits.stream().map(VectorStore.VectorHit::nodeId).toList(),
                neighbors, expandSources, isResourceSeeking(query));

        // 回捞扩展节点的 chunk 文本; 记录图谱路径 (哪个源节点经哪条边扩出)
        Map<String, VectorStore.VectorHit> byId = new HashMap<>();
        vecHits.forEach(h -> byId.put(h.nodeId(), h));
        Map<String, String> graphPath = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (GraphStore.Neighbor n : neighbors) {
            graphPath.putIfAbsent(n.dstId(), n.srcId() + " -[" + n.rel() + "]- " + n.dstId());
            if (!byId.containsKey(n.dstId())) missing.add(n.dstId());
        }
        byId.putAll(vectorStore.byNodeIds(missing));

        return fusedScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    VectorStore.VectorHit h = byId.get(e.getKey());
                    if (h == null) return null;
                    return new Evidence(h.nodeId(), h.nodeType(), h.chunkText(), e.getValue(), graphPath.get(h.nodeId()));
                })
                .filter(x -> x != null)
                .toList();
    }

    /**
     * 纯函数, 可单测: 向量排名RRF + 扩展节点(源排名RRF × α)。
     * 扩展贡献取【最优源的max】而非累加——首轮评估发现: 枢纽节点(被大量技能依赖的基础节点)
     * 从多源累加会挤掉真正的gold, single_hop切片从100%跌到73%。max抑制枢纽接管。
     */
    /** 找资源类查询: 非资源节点的扩展贡献打折, 防止扩展出的技能/岗位节点挤掉并列gold资源 */
    static final double NON_RESOURCE_DAMPEN = 0.4;
    private static final java.util.regex.Pattern RESOURCE_SEEKING =
            java.util.regex.Pattern.compile("课程|课吗|网课|视频|书|教材|教程|资源|题库|刷题|项目练|推荐.*(学|看|听)|(学|看|听).*推荐");

    static boolean isResourceSeeking(String query) {
        return RESOURCE_SEEKING.matcher(query).find();
    }

    static Map<String, Double> fuse(List<String> vectorRanking,
                                    List<GraphStore.Neighbor> neighbors,
                                    List<String> expandSources,
                                    boolean resourceSeeking) {
        Map<String, Double> score = new LinkedHashMap<>();
        for (int i = 0; i < vectorRanking.size(); i++) {
            score.merge(vectorRanking.get(i), 1.0 / (RRF_K + i + 1), Double::sum);
        }
        Map<String, Integer> srcRank = new HashMap<>();
        for (int i = 0; i < expandSources.size(); i++) srcRank.put(expandSources.get(i), i + 1);
        Map<String, Double> expandScore = new HashMap<>();
        for (GraphStore.Neighbor n : neighbors) {
            int rank = srcRank.getOrDefault(n.srcId(), expandSources.size() + 1);
            double contribution = ALPHA * (1.0 / (RRF_K + rank));
            if (resourceSeeking && !n.dstId().startsWith("res:")) contribution *= NON_RESOURCE_DAMPEN;
            expandScore.merge(n.dstId(), contribution, Double::max);
        }
        expandScore.forEach((id, s) -> score.merge(id, s, Double::sum));
        return score;
    }
}
