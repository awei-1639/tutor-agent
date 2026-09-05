package com.tutor.evaluation.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic scoring, release gates and badcase clustering for RAG evaluation. */
final class RagEvalQuality {
    private static final double Z_95 = 1.959963984540054;

    private RagEvalQuality() {}

    record GateThresholds(double minOverallHit, double minOverallRecall, double minMultiHopHit, long maxErrors) {}

    static Map<String, Object> aggregate(List<Map<String, Object>> rows) {
        Map<String, Object> result = new LinkedHashMap<>();
        long passed = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("hit"))).count();
        double hitAtK = average(rows, "hit", value -> Boolean.TRUE.equals(value) ? 1D : 0D);
        result.put("n", rows.size());
        result.put("passed", passed);
        result.put("hitAtK", hitAtK);
        result.put("hitAtKCi95", wilsonInterval(passed, rows.size()));
        result.put("recallAtK", average(rows, "recall", value -> ((Number) value).doubleValue()));
        result.put("mrr", average(rows, "rr", value -> ((Number) value).doubleValue()));
        List<Long> latencies = rows.stream().map(r -> ((Number) r.get("latencyMs")).longValue()).sorted().toList();
        result.put("p50Ms", percentile(latencies, 0.50));
        result.put("p95Ms", percentile(latencies, 0.95));
        result.put("errors", rows.stream().filter(r -> r.get("error") != null).count());
        return result;
    }

    /** Annotates each failed/partial case and returns stable issue clusters that can become backlog items. */
    static List<Map<String, Object>> diagnoseAndCluster(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> diagnosis = diagnose(row);
            if (diagnosis == null) continue;
            row.put("diagnosis", diagnosis);
            String code = (String) diagnosis.get("code");
            Map<String, Object> cluster = grouped.computeIfAbsent(code, ignored -> newCluster(diagnosis));
            cluster.put("count", ((Number) cluster.get("count")).longValue() + 1);
            @SuppressWarnings("unchecked")
            List<String> samples = (List<String>) cluster.get("sampleCaseIds");
            if (samples.size() < 5) samples.add((String) row.get("id"));
        }
        return grouped.values().stream()
                .sorted(Comparator.<Map<String, Object>, Long>comparing(c -> ((Number) c.get("count")).longValue()).reversed()
                        .thenComparing(c -> (String) c.get("code")))
                .toList();
    }

    static Map<String, Object> qualityGate(Map<String, Object> overall,
                                            Map<String, Object> byType,
                                            int evaluatedCases,
                                            int datasetCases,
                                            GateThresholds thresholds) {
        long errors = ((Number) overall.get("errors")).longValue();
        double hitAtK = ((Number) overall.get("hitAtK")).doubleValue();
        double recallAtK = ((Number) overall.get("recallAtK")).doubleValue();
        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(rule("execution_errors", "P0", "评测执行异常", errors, thresholds.maxErrors(), errors <= thresholds.maxErrors(), "max"));
        rules.add(rule("overall_hit", "P1", "总体案例通过率", hitAtK, thresholds.minOverallHit(), hitAtK >= thresholds.minOverallHit(), "min"));
        rules.add(rule("overall_recall", "P1", "总体 Gold 覆盖率", recallAtK, thresholds.minOverallRecall(), recallAtK >= thresholds.minOverallRecall(), "min"));

        @SuppressWarnings("unchecked")
        Map<String, Object> multiHop = (Map<String, Object>) byType.get("multi_hop_prereq");
        if (multiHop == null) {
            rules.add(rule("multi_hop_hit", "P1", "多跳前置案例通过率", null, thresholds.minMultiHopHit(), null, "min"));
        } else {
            double multiHopHit = ((Number) multiHop.get("hitAtK")).doubleValue();
            rules.add(rule("multi_hop_hit", "P1", "多跳前置案例通过率", multiHopHit, thresholds.minMultiHopHit(), multiHopHit >= thresholds.minMultiHopHit(), "min"));
        }

        boolean p0Pass = rules.stream().filter(r -> "P0".equals(r.get("level"))).allMatch(r -> Boolean.TRUE.equals(r.get("passed")));
        boolean p1Pass = rules.stream().filter(r -> "P1".equals(r.get("level")))
                .filter(r -> Boolean.TRUE.equals(r.get("applicable"))).allMatch(r -> Boolean.TRUE.equals(r.get("passed")));
        boolean fullGoldenSet = evaluatedCases == datasetCases;
        String status = !p0Pass ? "blocked" : !fullGoldenSet ? "sample_only" : p1Pass ? "passed" : "needs_review";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("releaseEligible", "passed".equals(status));
        result.put("fullGoldenSet", fullGoldenSet);
        result.put("evaluatedCases", evaluatedCases);
        result.put("datasetCases", datasetCases);
        result.put("rules", rules);
        return result;
    }

    private static Map<String, Object> diagnose(Map<String, Object> row) {
        String type = (String) row.get("type");
        if (row.get("error") != null) return diagnosis("execution_error", "执行异常", "P0", "工程", "检查依赖服务、超时和重试日志。");
        boolean hit = Boolean.TRUE.equals(row.get("hit"));
        double recall = ((Number) row.get("recall")).doubleValue();
        @SuppressWarnings("unchecked")
        List<String> retrieved = (List<String>) row.get("retrieved");
        if (!hit) {
            if ("resource_rec".equals(type) && retrieved.stream().noneMatch(id -> id.startsWith("res:"))) {
                return diagnosis("resource_type_mismatch", "资源意图召回了非资源节点", "P1", "检索/知识库", "为资源推荐增加类型过滤或重排，并补齐资源别名与元数据。");
            }
            if ("multi_hop_prereq".equals(type)) {
                return diagnosis("multi_hop_miss", "多跳前置节点未覆盖", "P1", "图谱检索", "按前置关系扩展候选，并以多目标覆盖率参与重排。");
            }
            if ("job_requirement".equals(type)) {
                return diagnosis("job_skill_coverage_gap", "岗位要求未命中目标技能", "P1", "检索/图谱", "将岗位要求拆为技能子目标，逐项召回并去重聚合。");
            }
            return diagnosis("gold_not_retrieved", "目标 Gold 节点未召回", "P1", "检索/知识库", "检查节点语料、别名同义词和查询改写覆盖。");
        }
        if (recall < 1D) {
            if ("multi_hop_prereq".equals(type)) {
                return diagnosis("multi_hop_partial_coverage", "多跳前置节点仅部分覆盖", "P1", "图谱检索", "提高多节点覆盖重排权重，避免只命中一个前置技能。");
            }
            if ("job_requirement".equals(type)) {
                return diagnosis("job_partial_coverage", "岗位技能仅部分覆盖", "P1", "检索/图谱", "按岗位技能槽位补召回，使用覆盖率而非单点命中作为目标。");
            }
            return diagnosis("partial_gold_coverage", "Gold 节点仅部分覆盖", "P2", "检索", "扩大候选集后做覆盖型重排，保留互补证据。");
        }
        return null;
    }

    private static Map<String, Object> newCluster(Map<String, Object> diagnosis) {
        Map<String, Object> cluster = new LinkedHashMap<>(diagnosis);
        cluster.put("count", 0L);
        cluster.put("sampleCaseIds", new ArrayList<String>());
        return cluster;
    }

    private static Map<String, Object> diagnosis(String code, String label, String severity, String owner, String suggestion) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("label", label);
        result.put("severity", severity);
        result.put("owner", owner);
        result.put("suggestion", suggestion);
        return result;
    }

    private static Map<String, Object> rule(String code, String level, String label, Number actual, Number threshold, Boolean passed, String comparator) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("level", level);
        result.put("label", label);
        result.put("actual", actual);
        result.put("threshold", threshold);
        result.put("comparator", comparator);
        result.put("applicable", actual != null);
        result.put("passed", passed);
        return result;
    }

    private static Map<String, Object> wilsonInterval(long successes, int n) {
        if (n == 0) return Map.of("lower", 0D, "upper", 0D);
        double p = (double) successes / n;
        double z2 = Z_95 * Z_95;
        double denominator = 1D + z2 / n;
        double center = (p + z2 / (2D * n)) / denominator;
        double margin = Z_95 * Math.sqrt((p * (1D - p) + z2 / (4D * n)) / n) / denominator;
        return Map.of("lower", Math.max(0D, center - margin), "upper", Math.min(1D, center + margin));
    }

    private static double average(List<Map<String, Object>> rows, String key, java.util.function.Function<Object, Double> mapper) {
        return rows.isEmpty() ? 0D : rows.stream().map(r -> mapper.apply(r.get(key))).mapToDouble(Double::doubleValue).average().orElse(0D);
    }

    private static long percentile(List<Long> values, double p) {
        if (values.isEmpty()) return 0L;
        return values.get(Math.min(values.size() - 1, (int) Math.floor(p * values.size())));
    }
}
