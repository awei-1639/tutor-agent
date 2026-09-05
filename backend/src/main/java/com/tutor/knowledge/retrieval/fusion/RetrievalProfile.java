package com.tutor.knowledge.retrieval.fusion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 经离线评测批准的检索调参档案。安全边界（关系白名单、作用域和最大跳数）不在此配置中。
 */
@Component
public record RetrievalProfile(
        String version,
        int vectorTopN,
        int rerankPool,
        int expandSourceN,
        int denseExpandSourceN,
        int sparseExpandSourceN,
        int expandPerSource,
        int expandLimit,
        int rrfK,
        double graphAlpha,
        double sparseBeta,
        double nonResourceDampen,
        double resourceDampen,
        double sparseThreshold) {

    public RetrievalProfile(
            @Value("${tutor.retrieval.profile.version:2026-08-baseline}") String version,
            @Value("${tutor.retrieval.profile.vector-top-n:20}") int vectorTopN,
            @Value("${tutor.retrieval.profile.rerank-pool:12}") int rerankPool,
            @Value("${tutor.retrieval.profile.expand-source-n:10}") int expandSourceN,
            @Value("${tutor.retrieval.profile.dense-expand-source-n:8}") int denseExpandSourceN,
            @Value("${tutor.retrieval.profile.sparse-expand-source-n:4}") int sparseExpandSourceN,
            @Value("${tutor.retrieval.profile.expand-per-source:6}") int expandPerSource,
            @Value("${tutor.retrieval.profile.expand-limit:40}") int expandLimit,
            @Value("${tutor.retrieval.profile.rrf-k:10}") int rrfK,
            @Value("${tutor.retrieval.profile.graph-alpha:0.85}") double graphAlpha,
            @Value("${tutor.retrieval.profile.sparse-beta:0.30}") double sparseBeta,
            @Value("${tutor.retrieval.profile.non-resource-dampen:0.40}") double nonResourceDampen,
            @Value("${tutor.retrieval.profile.resource-dampen:1.0}") double resourceDampen,
            @Value("${tutor.retrieval.profile.sparse-threshold:0.15}") double sparseThreshold) {
        version = version == null || version.isBlank() ? "2026-08-baseline" : version.trim();
        bounded(vectorTopN, 1, 100, "vector-top-n");
        bounded(rerankPool, 1, 50, "rerank-pool");
        bounded(expandSourceN, 1, 30, "expand-source-n");
        bounded(denseExpandSourceN, 0, expandSourceN, "dense-expand-source-n");
        bounded(sparseExpandSourceN, 0, expandSourceN, "sparse-expand-source-n");
        bounded(expandPerSource, 1, 20, "expand-per-source");
        bounded(expandLimit, 1, 100, "expand-limit");
        bounded(rrfK, 1, 100, "rrf-k");
        probability(graphAlpha, "graph-alpha", false);
        probability(sparseBeta, "sparse-beta", false);
        probability(nonResourceDampen, "non-resource-dampen", false);
        probability(resourceDampen, "resource-dampen", false);
        probability(sparseThreshold, "sparse-threshold", true);
        this.version = version;
        this.vectorTopN = vectorTopN;
        this.rerankPool = rerankPool;
        this.expandSourceN = expandSourceN;
        this.denseExpandSourceN = denseExpandSourceN;
        this.sparseExpandSourceN = sparseExpandSourceN;
        this.expandPerSource = expandPerSource;
        this.expandLimit = expandLimit;
        this.rrfK = rrfK;
        this.graphAlpha = graphAlpha;
        this.sparseBeta = sparseBeta;
        this.nonResourceDampen = nonResourceDampen;
        this.resourceDampen = resourceDampen;
        this.sparseThreshold = sparseThreshold;
    }

    private static void bounded(int value, int min, int max, String name) {
        if (value < min || value > max) throw new IllegalArgumentException("tutor.retrieval.profile." + name
                + " must be between " + min + " and " + max);
    }

    private static void probability(double value, String name, boolean inclusiveZero) {
        if (!Double.isFinite(value) || value > 1D || (inclusiveZero ? value < 0D : value <= 0D)) {
            throw new IllegalArgumentException("tutor.retrieval.profile." + name + " must be in "
                    + (inclusiveZero ? "[0,1]" : "(0,1]"));
        }
    }
}
