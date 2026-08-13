package com.tutor.retrieval.fusion;

import com.tutor.contract.Evidence;
import com.tutor.llm.LlmGateway;
import com.tutor.retrieval.graph.GraphStore;
import com.tutor.retrieval.vector.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(FusedRetriever.class);
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
                        return withScore(e, scores[i]);
                    })
                    .toList();
        } catch (RuntimeException ex) {
            // 降级: 重排不可用不阻断检索
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }
    }

    private List<Evidence> retrieveFused(String query, int topK, String traceId, boolean fused) {
        List<VectorStore.VectorHit> vecHits;
        try {
            float[] qv = gateway.embed(query, traceId);
            vecHits = vectorStore.search(qv, VECTOR_TOPN);
        } catch (RuntimeException error) {
            // Embedding/provider failure must not turn retrieval into a hard chat failure.
            // Sparse PostgreSQL search is deterministic and keeps the answer path usable.
            log.warn("向量检索不可用, 降级稀疏检索 trace={} type={}", traceId, error.getClass().getSimpleName());
            return sparseFallback(query, topK);
        }
        // 稀疏通道 (Phase 2 V4 2.1): pg_trgm 模糊匹配, 取 TOPN 同样的 20 候选
        List<VectorStore.VectorHit> sparseHits;
        try {
            sparseHits = vectorStore.sparseSearch(query, VECTOR_TOPN, 0.15);
        } catch (RuntimeException error) {
            log.warn("稀疏检索不可用, 保留向量结果 trace={} type={}", traceId, error.getClass().getSimpleName());
            sparseHits = List.of();
        }
        if (!fused) {
            return vecHits.stream().limit(topK)
                    .map(h -> evidence(h, h.score(), null))
                    .toList();
        }

        List<String> expandSources = vecHits.stream().limit(EXPAND_SOURCE_N)
                .map(VectorStore.VectorHit::nodeId).toList();
        List<GraphStore.Neighbor> neighbors = graphStore.expand(expandSources, EXPAND_PER_SOURCE, EXPAND_LIMIT);

        Map<String, Double> fusedScores = fuse(
                vecHits.stream().map(VectorStore.VectorHit::nodeId).toList(),
                sparseHits.stream().map(VectorStore.VectorHit::nodeId).toList(),
                neighbors, expandSources, isResourceSeeking(query));

        // 回捞扩展节点的 chunk 文本; 记录图谱路径 (哪个源节点经哪条边扩出)
        Map<String, VectorStore.VectorHit> byId = new HashMap<>();
        vecHits.forEach(h -> byId.put(h.nodeId(), h));
        sparseHits.forEach(h -> byId.putIfAbsent(h.nodeId(), h));
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
                    return evidence(h, e.getValue(), graphPath.get(h.nodeId()));
                })
                .filter(x -> x != null)
                .toList();
    }

    private List<Evidence> sparseFallback(String query, int topK) {
        try {
            return vectorStore.sparseSearch(query, Math.max(topK, VECTOR_TOPN), 0.15).stream()
                    .limit(topK)
                    .map(h -> evidence(h, h.score(), null))
                    .toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static Evidence evidence(VectorStore.VectorHit hit, double score, String graphPath) {
        return new Evidence(hit.nodeId(), hit.nodeType(), hit.chunkText(), score, graphPath,
                hit.sourceUrl(), hit.sourceStatus(), hit.contentHash());
    }

    private static Evidence withScore(Evidence evidence, double score) {
        return new Evidence(evidence.nodeId(), evidence.nodeType(), evidence.chunkText(), score,
                evidence.graphPath(), evidence.sourceUrl(), evidence.sourceStatus(), evidence.contentHash());
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
        return fuse(vectorRanking, List.of(), neighbors, expandSources, resourceSeeking);
    }

    /**
     * 三路 RRF (Phase 2 V4 2.1): 稠密+稀疏+图扩展。稀疏通道用 BETA 衰减,
     * 与 ALPHA 隔离以便独立调参; 阈值见 {@link VectorStore#sparseSearch}。
     */
    static Map<String, Double> fuse(List<String> vectorRanking,
                                    List<String> sparseRanking,
                                    List<GraphStore.Neighbor> neighbors,
                                    List<String> expandSources,
                                    boolean resourceSeeking) {
        Map<String, Double> score = new LinkedHashMap<>();
        for (int i = 0; i < vectorRanking.size(); i++) {
            score.merge(vectorRanking.get(i), 1.0 / (RRF_K + i + 1), Double::sum);
        }
        // 稀疏通道: 与图扩展相同的 max 抑制策略, 防止同一节点被多个稀疏片段反复加分
        Map<String, Double> sparseScore = new HashMap<>();
        for (int i = 0; i < sparseRanking.size(); i++) {
            String id = sparseRanking.get(i);
            double contribution = BETA * (1.0 / (RRF_K + i + 1));
            // 资源查询: 非资源节点稀疏命中也抑制, 防止 trigram 模糊命中技能/岗位挤掉真正的资源 gold
            if (resourceSeeking && !id.startsWith("res:")) {
                contribution *= NON_RESOURCE_DAMPEN;
            }
            sparseScore.merge(id, contribution, Double::max);
        }
        sparseScore.forEach((id, s) -> score.merge(id, s, Double::sum));

        Map<String, Integer> srcRank = new HashMap<>();
        for (int i = 0; i < expandSources.size(); i++) {
            srcRank.put(expandSources.get(i), i + 1);
        }
        Map<String, Double> expandScore = new HashMap<>();
        for (GraphStore.Neighbor n : neighbors) {
            int rank = srcRank.getOrDefault(n.srcId(), expandSources.size() + 1);
            double contribution = ALPHA * (1.0 / (RRF_K + rank));
            if (resourceSeeking && !n.dstId().startsWith("res:")) {
                contribution *= NON_RESOURCE_DAMPEN;
            }
            expandScore.merge(n.dstId(), contribution, Double::max);
        }
        expandScore.forEach((id, s) -> score.merge(id, s, Double::sum));
        return score;
    }

    /** 稀疏通道权重 (Phase 2 V4 2.1): pg_trgm 兜底专有名词漏召, 不应主导排序。0.3 ≈ 图扩展(0.85)的 1/3,
     * 仅当向量命中失败时 sparse 命中节点才能进入 top5; BETA 过高会让 trigram 命中挤掉向量近邻导致资源切片退化。 */
    static final double BETA = 0.3;
}
