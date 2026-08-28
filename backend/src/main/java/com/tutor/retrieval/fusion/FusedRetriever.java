package com.tutor.retrieval.fusion;

import com.tutor.contract.Evidence;
import com.tutor.llm.EmbeddingGateway;
import com.tutor.llm.RerankGateway;
import com.tutor.retrieval.GraphScope;
import com.tutor.retrieval.graph.GraphExpansionPolicy;
import com.tutor.retrieval.graph.GraphStore;
import com.tutor.retrieval.vector.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 融合检索: dense/sparse top20 → 按语义策略的有向图一跳扩展 → RRF融合 → topK。
 * RRF: score = Σ 1/(K + rank); 扩展节点得分乘 α。
 */
@Component
public class FusedRetriever {
    private static final Logger log = LoggerFactory.getLogger(FusedRetriever.class);
    /** α>0.733才能让rank1源的扩展节点(α/(K+1))超过向量第5名(1/(K+5))——否则图谱通道无法触及top5 */
    static final double ALPHA = 0.85;
    /**
     * RRF K=10 (非常规60): K=60会把分差拉平, 纯扩展节点数学上永远低于向量第20名,
     * 图谱通道形同虚设; K=10下rank1源的扩展分(α/11≈0.077)可与向量rank5-8竞争——评估调参结论。
     */
    static final int RRF_K = 10;
    static final int VECTOR_TOPN = 20;
    static final int EXPAND_SOURCE_N = 10;  // dense/sparse 联合扩展起点总数
    static final int DENSE_EXPAND_SOURCE_N = 8;
    static final int SPARSE_EXPAND_SOURCE_N = 4;
    static final int EXPAND_PER_SOURCE = 6; // 每个源节点最多取 6 个邻居
    static final int EXPAND_LIMIT = 40; // 所有源节点总共最多取 40 个邻居

    private final EmbeddingGateway embeddings;
    private final RerankGateway reranker;
    private final VectorStore vectorStore;
    private final GraphStore graphStore;
    private final EntityAliasStore aliasStore;
    private final RetrievalProfile profile;
    /** 默认禁用；仅在离线评测确认收益后启用。 */
    @Value("${tutor.retrieval.channel-floor.enabled:false}")
    private boolean channelFloorEnabled;

    public FusedRetriever(EmbeddingGateway embeddings, RerankGateway reranker, VectorStore vectorStore, GraphStore graphStore,
                          EntityAliasStore aliasStore, RetrievalProfile profile) {
        this.embeddings = embeddings;
        this.reranker = reranker;
        this.vectorStore = vectorStore;
        this.graphStore = graphStore;
        this.aliasStore = aliasStore;
        this.profile = profile;
    }

    /** 当前检索调参档案的稳定标识，用于追踪与反馈归因。 */
    public String profileVersion() {
        return profile.version();
    }

    static final int RERANK_POOL = 12; // 融合后进入重排的候选数

    /**
     * 评估三模式: vector_only / fused / fused_rerank(查询自适应)。
     * 重排只用于"找资源"类查询——三方评估结论: reranker使resource_rec 66.7→86.9%(反超向量基线),
     * 但会降权图扩展节点(job切片35→11.7%), 因为技能chunk文本"看不出"在回答岗位问题而它们正是gold。
     * 选择规则仅依赖查询文本, 不依赖标签。重排失败降级保持融合排序 (降级矩阵)。
     */
    public RetrievalOutcome retrieve(String query, int topK, String traceId, boolean fused, boolean rerank,
                                     GraphExpansionPolicy graphPolicy, GraphScope scope) {
        boolean rerankEffective = rerank && isResourceSeeking(query);
        CandidateOutcome candidateOutcome = retrieveFused(query,
                rerankEffective ? Math.max(topK, profile.rerankPool()) : topK,
                traceId, fused, graphPolicy, scope);
        List<Evidence> candidates = candidateOutcome.evidences();
        if (!rerankEffective || candidates.size() <= topK) {
            return new RetrievalOutcome(candidates.subList(0, Math.min(topK, candidates.size())),
                    candidateOutcome.telemetry());
        }
        try {
            double[] scores = reranker.rerank(query, candidates.stream().map(Evidence::chunkText).toList(), traceId);
            List<Evidence> reranked = java.util.stream.IntStream.range(0, candidates.size()).boxed()
                    .sorted((a, b) -> Double.compare(scores[b], scores[a]))
                    .limit(topK)
                    .map(i -> {
                        Evidence e = candidates.get(i);
                        return withScore(e, scores[i]);
                    })
                    .toList();
            return new RetrievalOutcome(reranked, candidateOutcome.telemetry().withRerank(true, false));
        } catch (RuntimeException ex) {
            // 降级: 重排不可用不阻断检索
            return new RetrievalOutcome(candidates.subList(0, Math.min(topK, candidates.size())),
                    candidateOutcome.telemetry().withRerank(false, true));
        }
    }

    private CandidateOutcome retrieveFused(String query, int topK, String traceId, boolean fused,
                                           GraphExpansionPolicy graphPolicy, GraphScope scope) {
        GraphScope effectiveScope = scope == null ? GraphScope.publicOnly() : scope;
        List<VectorStore.VectorHit> vecHits;
        try {
            float[] qv = embeddings.embedQuery(query, traceId);
            vecHits = vectorStore.search(qv, profile.vectorTopN(), effectiveScope);
        } catch (RuntimeException error) {
            // Embedding 或 Provider 故障不能把检索升级为聊天硬失败；确定性的 PostgreSQL 稀疏检索仍可保障回答路径可用。
            log.warn("向量检索不可用, 降级稀疏检索 trace={} type={}", traceId, error.getClass().getSimpleName());
            List<Evidence> fallback = sparseFallback(query, topK, effectiveScope);
            return new CandidateOutcome(fallback, new RetrievalTelemetry(0, fallback.size(), 0, 0,
                    true, false, false, false));
        }
        if (!fused) {
            return new CandidateOutcome(vecHits.stream().limit(topK)
                    .map(h -> evidence(h, h.score(), null))
                    .toList(), new RetrievalTelemetry(vecHits.size(), 0, 0, 0,
                    false, false, false, false));
        }
        // 稀疏通道 (Phase 2 V4 2.1): pg_trgm 模糊匹配, 取 TOPN 同样的 20 候选
        List<VectorStore.VectorHit> sparseHits;
        boolean sparseDegraded = false;
        try {
            sparseHits = vectorStore.sparseSearch(query, profile.vectorTopN(), profile.sparseThreshold(), effectiveScope);
        } catch (RuntimeException error) {
            log.warn("稀疏检索不可用, 保留向量结果 trace={} type={}", traceId, error.getClass().getSimpleName());
            sparseHits = List.of();
            sparseDegraded = true;
        }
        List<String> aliasSources = List.of();
        if (graphPolicy != null && graphPolicy.enabled()) {
            LinkedHashSet<String> aliases = new LinkedHashSet<>();
            if (aliasStore != null) {
                List<String> aliasNodeSources = aliasStore.resolveNodeIds(seedAliases(query), 4);
                if (aliasNodeSources != null) aliases.addAll(aliasNodeSources);
            }
            List<String> graphAliasSources = graphStore.findSeedIds(seedAliases(query), 4, effectiveScope);
            if (graphAliasSources != null) aliases.addAll(graphAliasSources);
            aliasSources = aliases.stream().limit(2).toList();
        }
        List<String> expandSources = selectExpansionSourcesWithProfile(vecHits, sparseHits, graphPolicy, aliasSources);
        List<GraphStore.Neighbor> neighbors = graphStore.expand(
                expandSources, profile.expandPerSource(), profile.expandLimit(), graphPolicy, effectiveScope);

        Set<String> resourceNodeIds = new LinkedHashSet<>();
        vecHits.stream().filter(FusedRetriever::isResourceHit)
                .map(VectorStore.VectorHit::nodeId).forEach(resourceNodeIds::add);
        sparseHits.stream().filter(FusedRetriever::isResourceHit)
                .map(VectorStore.VectorHit::nodeId).forEach(resourceNodeIds::add);
        Map<String, Double> fusedScores = fuseWithProfile(
                vecHits.stream().map(VectorStore.VectorHit::nodeId).toList(),
                sparseHits.stream().map(VectorStore.VectorHit::nodeId).toList(),
                neighbors, expandSources, isResourceSeeking(query), graphPolicy, resourceNodeIds);

        // 回捞扩展节点的 chunk 文本; 记录图谱路径 (哪个源节点经哪条边扩出)
        Map<String, VectorStore.VectorHit> byId = new HashMap<>();
        vecHits.forEach(h -> byId.put(h.nodeId(), h));
        sparseHits.forEach(h -> byId.putIfAbsent(h.nodeId(), h));
        Map<String, String> graphPath = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (GraphStore.Neighbor n : neighbors) {
            graphPath.putIfAbsent(n.dstId(), formatGraphPath(n));
            if (!byId.containsKey(n.dstId())) missing.add(n.dstId());
        }
        byId.putAll(vectorStore.byNodeIds(missing, effectiveScope));

        List<Evidence> ranked = fusedScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> {
                    VectorStore.VectorHit h = byId.get(e.getKey());
                    if (h == null) return null;
                    return evidence(h, e.getValue(), graphPath.get(h.nodeId()));
                })
                .filter(x -> x != null)
                .toList();
        List<Evidence> selected = !channelFloorEnabled ? ranked.stream().limit(topK).toList() : ensureChannelFloor(ranked, topK,
                vecHits.stream().map(VectorStore.VectorHit::nodeId).collect(java.util.stream.Collectors.toSet()),
                sparseHits.stream().map(VectorStore.VectorHit::nodeId).collect(java.util.stream.Collectors.toSet()),
                neighbors.stream().map(GraphStore.Neighbor::dstId).collect(java.util.stream.Collectors.toSet()));
        return new CandidateOutcome(selected, new RetrievalTelemetry(vecHits.size(), sparseHits.size(),
                neighbors.size(), expandSources.size(), false, sparseDegraded, false, false));
    }

    private record CandidateOutcome(List<Evidence> evidences, RetrievalTelemetry telemetry) {
    }

    public record RetrievalOutcome(List<Evidence> evidences, RetrievalTelemetry telemetry) {
        public RetrievalOutcome {
            evidences = evidences == null ? List.of() : List.copyOf(evidences);
            telemetry = telemetry == null ? RetrievalTelemetry.empty() : telemetry;
        }
    }

    /** 候选池观测值；所有数量均为实际查询返回数，而非配置上限。 */
    public record RetrievalTelemetry(int denseCandidates, int sparseCandidates, int graphCandidates,
                                     int graphExpansionSources, boolean embeddingDegraded,
                                     boolean sparseDegraded, boolean rerankApplied, boolean rerankDegraded) {
        public RetrievalTelemetry {
            denseCandidates = Math.max(0, denseCandidates);
            sparseCandidates = Math.max(0, sparseCandidates);
            graphCandidates = Math.max(0, graphCandidates);
            graphExpansionSources = Math.max(0, graphExpansionSources);
        }

        public static RetrievalTelemetry empty() {
            return new RetrievalTelemetry(0, 0, 0, 0, false, false, false, false);
        }

        public RetrievalTelemetry plus(RetrievalTelemetry other) {
            if (other == null) return this;
            return new RetrievalTelemetry(denseCandidates + other.denseCandidates,
                    sparseCandidates + other.sparseCandidates, graphCandidates + other.graphCandidates,
                    graphExpansionSources + other.graphExpansionSources,
                    embeddingDegraded || other.embeddingDegraded, sparseDegraded || other.sparseDegraded,
                    rerankApplied || other.rerankApplied, rerankDegraded || other.rerankDegraded);
        }

        public RetrievalTelemetry withRerank(boolean applied, boolean degraded) {
            return new RetrievalTelemetry(denseCandidates, sparseCandidates, graphCandidates, graphExpansionSources,
                    embeddingDegraded, sparseDegraded, applied, degraded);
        }
    }

    static List<Evidence> ensureChannelFloor(List<Evidence> ranked, int topK,
                                             Set<String> denseIds, Set<String> sparseIds,
                                             Set<String> graphIds) {
        if (topK <= 0 || ranked.isEmpty()) return List.of();
        List<Evidence> selected = new ArrayList<>();
        Set<String> selectedIds = new LinkedHashSet<>();
        for (Set<String> channel : List.of(denseIds, sparseIds, graphIds)) {
            ranked.stream().filter(e -> channel.contains(e.nodeId()))
                    .findFirst().ifPresent(e -> {
                        if (selectedIds.add(e.nodeId())) selected.add(e);
                    });
        }
        ranked.stream().filter(e -> selectedIds.add(e.nodeId())).forEach(selected::add);
        return selected.stream().limit(topK).toList();
    }

    /**
     * 仅扩展上一跳中由图谱派生的前沿节点。这与查询检索刻意分离：它保留真实的 A->B->C 路径，
     * 而不是将每一跳都当成新的 top-k 种子检索。
     */
    public List<Evidence> expandFrontier(List<Evidence> frontier, int topK,
                                         GraphExpansionPolicy policy, GraphScope scope) {
        if (frontier == null || frontier.isEmpty() || topK <= 0 || policy == null || !policy.enabled()) {
            return List.of();
        }
        Map<String, Evidence> sourceById = new LinkedHashMap<>();
        frontier.stream()
                .filter(e -> e != null && e.nodeId() != null && !e.nodeId().isBlank())
                .forEach(e -> sourceById.putIfAbsent(e.nodeId(), e));
        if (sourceById.isEmpty()) return List.of();

        List<GraphStore.Neighbor> neighbors = graphStore.expand(
                sourceById.keySet().stream().toList(), profile.expandPerSource(),
                Math.min(profile.expandLimit(), Math.max(topK * 4, topK)), policy,
                scope == null ? GraphScope.publicOnly() : scope);
        if (neighbors.isEmpty()) return List.of();
        Map<String, VectorStore.VectorHit> hits = vectorStore.byNodeIds(
                neighbors.stream().map(GraphStore.Neighbor::dstId).distinct().toList(),
                scope == null ? GraphScope.publicOnly() : scope);
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
            double score = parentScore * ALPHA * rule.weight()
                    * Math.max(0D, Math.min(1D, neighbor.confidence()));
            String path = appendGraphPath(source.graphPath(), formatGraphPath(neighbor));
            result.add(evidence(hit, score, path));
            if (result.size() >= topK) break;
        }
        return result;
    }

    private static String appendGraphPath(String prefix, String current) {
        if (prefix == null || prefix.isBlank()) return current;
        return prefix + " | " + current;
    }

    /**
     * 稠密和稀疏候选都获得成为图扩展源的有限机会。稠密通道保留更大配额，稀疏通道则保留
     * Embedding 可能遗漏的罕见术语或缩写命中。
     */
    static List<String> selectExpansionSources(List<VectorStore.VectorHit> dense,
                                                List<VectorStore.VectorHit> sparse,
                                                GraphExpansionPolicy policy) {
        return selectExpansionSources(dense, sparse, policy, List.of());
    }

    /** 选择图扩展源，并从普通候选配额中预留最多两个别名源名额。 */
    static List<String> selectExpansionSources(List<VectorStore.VectorHit> dense,
                                                List<VectorStore.VectorHit> sparse,
                                                GraphExpansionPolicy policy,
                                                List<String> aliases) {
        if (policy == null || !policy.enabled()) return List.of();
        List<String> aliasSources = aliases == null ? List.of() : aliases.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .limit(2)
                .toList();
        int baseSourceLimit = Math.max(0, EXPAND_SOURCE_N - aliasSources.size());
        Set<String> ids = new LinkedHashSet<>();
        if (dense != null) dense.stream()
                .filter(h -> h != null && h.nodeId() != null && h.score() >= policy.minSourceScore())
                .limit(DENSE_EXPAND_SOURCE_N)
                .map(VectorStore.VectorHit::nodeId)
                .forEach(ids::add);
        if (sparse != null) sparse.stream()
                .filter(h -> h != null && h.nodeId() != null && h.score() >= policy.minSourceScore())
                .limit(SPARSE_EXPAND_SOURCE_N)
                .map(VectorStore.VectorHit::nodeId)
                .forEach(ids::add);
        LinkedHashSet<String> result = new LinkedHashSet<>(ids.stream().limit(baseSourceLimit).toList());
        result.addAll(aliasSources);
        return result.stream().limit(EXPAND_SOURCE_N).toList();
    }

    private List<String> selectExpansionSourcesWithProfile(List<VectorStore.VectorHit> dense,
                                                           List<VectorStore.VectorHit> sparse,
                                                           GraphExpansionPolicy policy,
                                                           List<String> aliasSources) {
        List<String> safeAliases = aliasSources == null ? List.of() : aliasSources.stream()
                .filter(id -> id != null && !id.isBlank()).distinct().limit(2).toList();
        int baseSourceLimit = Math.max(0, profile.expandSourceN() - safeAliases.size());
        Set<String> ids = new LinkedHashSet<>();
        if (dense != null) dense.stream()
                .filter(h -> h != null && h.nodeId() != null && h.score() >= policy.minSourceScore())
                .limit(profile.denseExpandSourceN()).map(VectorStore.VectorHit::nodeId).forEach(ids::add);
        if (sparse != null) sparse.stream()
                .filter(h -> h != null && h.nodeId() != null && h.score() >= policy.minSourceScore())
                .limit(profile.sparseExpandSourceN()).map(VectorStore.VectorHit::nodeId).forEach(ids::add);
        LinkedHashSet<String> result = new LinkedHashSet<>(ids.stream().limit(baseSourceLimit).toList());
        result.addAll(safeAliases);
        return result.stream().limit(profile.expandSourceN()).toList();
    }

    static List<String> seedAliases(String query) {
        if (query == null) return List.of();
        String mixed = query.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("(?<=[\\p{IsHan}])(?=[a-z0-9])", " ")
                .replaceAll("(?<=[a-z0-9])(?=[\\p{IsHan}])", " ");
        return java.util.Arrays.stream(mixed
                        .split("[^\\p{L}\\p{N}_-]+"))
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .filter(token -> !Set.of("什么", "如何", "哪些", "怎么", "需要", "推荐", "学习",
                        "教程", "资料", "材料", "适合", "入门", "基础", "资源").contains(token))
                .distinct().limit(16).toList();
    }

    private static String formatGraphPath(GraphStore.Neighbor neighbor) {
        String path = neighbor.direction() == GraphExpansionPolicy.Direction.INCOMING
                ? neighbor.dstId() + " -[" + neighbor.rel() + "]-> " + neighbor.srcId()
                : neighbor.srcId() + " -[" + neighbor.rel() + "]-> " + neighbor.dstId();
        return path + " (置信度=" + String.format(java.util.Locale.ROOT, "%.2f", neighbor.confidence())
                + ", 来源=" + neighbor.source() + ")";
    }

    private List<Evidence> sparseFallback(String query, int topK, GraphScope scope) {
        try {
            return vectorStore.sparseSearch(query, Math.max(topK, profile.vectorTopN()), profile.sparseThreshold(), scope).stream()
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

    static boolean isResourceSeeking(String query) {
        return ResourceQueryClassifier.classify(query).seeking();
    }

    static ResourceQueryClassifier.Decision classifyResourceQuery(String query) {
        return ResourceQueryClassifier.classify(query);
    }

    private static boolean isResourceHit(VectorStore.VectorHit hit) {
        if (hit == null) return false;
        String type = hit.nodeType() == null ? "" : hit.nodeType().toLowerCase();
        String status = hit.sourceStatus() == null ? "" : hit.sourceStatus().toLowerCase();
        return type.equals("resource") || type.equals("document") || status.equals("managed")
                || isResourceNodeId(hit.nodeId());
    }

    private static boolean isResourceNodeId(String id) {
        return id != null && (id.startsWith("res:") || id.startsWith("doc:")
                || id.startsWith("document:"));
    }

    /**
     * 三路 RRF (Phase 2 V4 2.1): 稠密+稀疏+图扩展。稀疏通道用 BETA 衰减,
     * 与 ALPHA 隔离以便独立调参; 阈值见 {@link VectorStore#sparseSearch}。
     */
    static Map<String, Double> fuse(List<String> vectorRanking,
                                    List<String> sparseRanking,
                                    List<GraphStore.Neighbor> neighbors,
                                    List<String> expandSources,
                                    boolean resourceSeeking,
                                    GraphExpansionPolicy policy) {
        return fuse(vectorRanking, sparseRanking, neighbors, expandSources, resourceSeeking,
                policy, Set.of());
    }

    static Map<String, Double> fuse(List<String> vectorRanking,
                                    List<String> sparseRanking,
                                    List<GraphStore.Neighbor> neighbors,
                                    List<String> expandSources,
                                    boolean resourceSeeking,
                                    GraphExpansionPolicy policy,
                                    Set<String> resourceNodeIds) {
        return RrfFusionPolicy.fuse(vectorRanking, sparseRanking, neighbors, expandSources, resourceSeeking,
                policy, resourceNodeIds, RRF_K, ALPHA, BETA, NON_RESOURCE_DAMPEN);
    }

    private Map<String, Double> fuseWithProfile(List<String> vectorRanking,
                                                 List<String> sparseRanking,
                                                 List<GraphStore.Neighbor> neighbors,
                                                 List<String> expandSources,
                                                 boolean resourceSeeking,
                                                 GraphExpansionPolicy policy,
                                                 Set<String> resourceNodeIds) {
        return RrfFusionPolicy.fuse(vectorRanking, sparseRanking, neighbors, expandSources, resourceSeeking,
                policy, resourceNodeIds, profile.rrfK(), profile.graphAlpha(), profile.sparseBeta(),
                profile.nonResourceDampen());
    }

    /** 稀疏通道权重 (Phase 2 V4 2.1): pg_trgm 兜底专有名词漏召, 不应主导排序。0.3 ≈ 图扩展(0.85)的 1/3,
     * 仅当向量命中失败时 sparse 命中节点才能进入 top5; BETA 过高会让 trigram 命中挤掉向量近邻导致资源切片退化。 */
    static final double BETA = 0.3;
}
