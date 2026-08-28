package com.tutor.retrieval.agentic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.tutor.contract.Evidence;
import com.tutor.llm.RetrievalJudge;
import com.tutor.retrieval.GraphScope;
import com.tutor.retrieval.fusion.FusedRetriever;
import com.tutor.retrieval.graph.GraphExpansionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agentic 多跳检索 (Phase 2 V4 2.1): 单跳证据不足时改写查询二次检索, 跳数上限 3。
 * 触发: 查询含"前置/从零/学习顺序/路径/先学/学完"等学习路径关键词。
 * 降级矩阵: judge 失败/解析失败 → 视为充分, 直接返回当前证据。
 */
@Component
public class AgenticRetriever {
    private static final Logger log = LoggerFactory.getLogger(AgenticRetriever.class);
    static final int MAX_HOPS = 3;
    private static final double HOP_DECAY_FACTOR = 0.6;
    private final FusedRetriever fusedRetriever;
    private final RetrievalJudge judge;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public AgenticRetriever(FusedRetriever fusedRetriever, RetrievalJudge judge) {
        this.fusedRetriever = fusedRetriever;
        this.judge = judge;
    }

    public String retrievalProfileVersion() {
        return fusedRetriever.profileVersion();
    }

    public RetrievalResult retrieveAdaptiveResult(String query, int topK, String traceId,
                                                  boolean multiHopCandidate,
                                                  GraphExpansionPolicy graphPolicy,
                                                  GraphScope scope) {
        if (graphPolicy == null || scope == null) {
            throw new IllegalArgumentException("graphPolicy 和 scope 不能为空");
        }
        // 非候选请求始终走低成本单跳路径。
        if (!multiHopCandidate) {
            FusedRetriever.RetrievalOutcome outcome = retrieveOne(query, topK, traceId, graphPolicy, scope);
            return new RetrievalResult(outcome.evidences(), 1, false, "policy_single", outcome.telemetry());
        }

        Map<String, Evidence> byId = new LinkedHashMap<>();
        String currentQuery = query;
        Set<String> seenQueries = new LinkedHashSet<>();
        seenQueries.add(RetrievalQueryGuard.normalizeQuery(query));
        List<Evidence> graphFrontier = List.of();
        int hops = 0;
        String stopReason = "max_hops";
        FusedRetriever.RetrievalTelemetry telemetry = FusedRetriever.RetrievalTelemetry.empty();

        for (int hop = 1; hop <= MAX_HOPS; hop++) {
            hops = hop;
            // 多跳用更大的候选池 (topK*2), 让改写查询带来的新节点有空间, 同时保留 hop1 高分 gold
            int hopTopK = Math.max(topK * 2, 10);

            FusedRetriever.RetrievalOutcome queryOutcome = retrieveOne(currentQuery, hopTopK, traceId, graphPolicy, scope);
            telemetry = telemetry.plus(queryOutcome.telemetry());
            List<Evidence> queryResults = queryOutcome.evidences();
            if (queryResults == null) queryResults = List.of();
            List<Evidence> graphResults = List.of();
            if (hop > 1 && !graphFrontier.isEmpty()) {
                try {
                    graphResults = fusedRetriever.expandFrontier(graphFrontier, hopTopK, graphPolicy, scope);
                    telemetry = telemetry.plus(new FusedRetriever.RetrievalTelemetry(0, 0, graphResults.size(),
                            graphFrontier.size(), false, false, false, false));
                } catch (RuntimeException ex) {
                    log.warn("图前沿扩展失败，保留查询检索结果 hop={} trace={}", hop, traceId);
                }
            }
            List<Evidence> hopResults = boundHopScores(
                    mergeHopResults(queryResults, graphResults, hopTopK));
            if (hopResults.isEmpty()) {
                // 没有证据时，继续调用判定器或 Embedding 都无法改善回答。
                stopReason = "no_evidence";
                break;
            }
            // 累积 (RRF 融合分累加: 多跳命中的节点更相关)
            // 关键: hop1 满分, 后续跳按 0.6 衰减; hop1 已有 gold 不会被后续跳噪声挤掉
            double hopWeight = Math.pow(HOP_DECAY_FACTOR, hop - 1);
            for (Evidence evidence : hopResults) {
                Evidence weightedEvidence = withScore(
                        evidence,
                        evidence.score() * hopWeight
                );

                byId.merge(
                        weightedEvidence.nodeId(),
                        weightedEvidence,
                        this::mergeEvidence
                );
            }

            // 仅图谱派生证据可作为下一跳前沿；独立的向量命中不能伪装成连续图路径。
            graphFrontier = hop == 1
                    ? queryResults.stream()
                    .filter(e -> e.graphPath() != null && !e.graphPath().isBlank()).toList()
                    : graphResults;

            // 最后一跳不再 judge, 直接退出 (节省成本)
            if (hop == MAX_HOPS) {
                stopReason = "max_hops";
                break;
            }

            // judge 充分性
            String verdict;
            try {
                List<Evidence> strongestEvidence = byId.values().stream()
                        .sorted(java.util.Comparator.comparingDouble(Evidence::score).reversed()
                                .thenComparing(Evidence::nodeId, java.util.Comparator.nullsLast(String::compareTo)))
                        .limit(8)
                        .toList();
                verdict = judge.judgeSufficientWithEvidence(query, currentQuery, strongestEvidence, traceId);
            } catch (Exception ex) {
                // 判定器失败不是再消耗两次 Embedding 调用的理由；保留已收集证据交由回答路径处理。
                log.warn("judge 失败, 停止多跳并保留已有证据 hop={} trace={}", hop, traceId);
                stopReason = "judge_failure";
                break;
            }
            JudgeDecision d = parse(verdict);
            if (d == null) {
                // 判定器响应格式错误属于 Provider 故障，不能转化为下一次 Embedding 跳并放大故障成本。
                log.warn("judge 返回非法 JSON, 停止多跳并保留已有证据 hop={} trace={}", hop, traceId);
                stopReason = "judge_invalid";
                break;
            }
            // 只有 judge 明确判定充分 且 无 followup 才跳出; 否则继续下一跳
            if (d.sufficient) {
                stopReason = "judge_sufficient";
                break;
            }
            String candidate = RetrievalQueryGuard.sanitize(
                    query, currentQuery, d.followupQuery, seenQueries);
            if (candidate == null) {
                candidate = RetrievalQueryGuard.sanitize(
                        query, currentQuery,
                        RetrievalQueryGuard.missingFallback(query, d.missing),
                        seenQueries);
            }
            if (candidate == null) {
                candidate = RetrievalQueryGuard.sanitize(
                        query, currentQuery,
                        RetrievalQueryGuard.narrowFallback(query, hop),
                        seenQueries);
            }
            if (candidate == null) {
                stopReason = "followup_invalid";
                break;
            }
            currentQuery = candidate;
            seenQueries.add(RetrievalQueryGuard.normalizeQuery(currentQuery));
            log.info("多跳 hop={} 改写查询: {} → {}", hop, query, currentQuery);
        }

        List<Evidence> results = byId.values().stream()
                .sorted(java.util.Comparator.comparingDouble(Evidence::score).reversed()
                        .thenComparing(Evidence::nodeId, java.util.Comparator.nullsLast(String::compareTo)))
                .limit(topK)
                .toList();
        return new RetrievalResult(results, hops, true, stopReason, telemetry);
    }

    private FusedRetriever.RetrievalOutcome retrieveOne(String query, int topK, String traceId,
                                                         GraphExpansionPolicy graphPolicy, GraphScope scope) {
        return fusedRetriever.retrieve(query, topK, traceId, true, true, graphPolicy, scope);
    }

    private static List<Evidence> mergeHopResults(List<Evidence> queryResults,
                                                   List<Evidence> graphResults,
                                                   int limit) {
        Map<String, Evidence> merged = new LinkedHashMap<>();
        if (queryResults != null) {
            queryResults.stream().filter(e -> e != null && e.nodeId() != null)
                    .forEach(e -> merged.merge(e.nodeId(), e, AgenticRetriever::preferEvidence));
        }
        if (graphResults != null) {
            graphResults.stream().filter(e -> e != null && e.nodeId() != null)
                    .forEach(e -> merged.merge(e.nodeId(), e, AgenticRetriever::preferEvidence));
        }
        return merged.values().stream()
                .sorted(java.util.Comparator.comparingDouble(Evidence::score).reversed()
                        .thenComparing(Evidence::nodeId, java.util.Comparator.nullsLast(String::compareTo)))
                .limit(Math.max(1, limit))
                .toList();
    }

    private static Evidence preferEvidence(Evidence left, Evidence right) {
        boolean leftPath = left.graphPath() != null && !left.graphPath().isBlank();
        boolean rightPath = right.graphPath() != null && !right.graphPath().isBlank();
        int scoreOrder = Double.compare(right.score(), left.score());
        if (scoreOrder > 0) return right;
        if (scoreOrder < 0) return left;
        return rightPath && !leftPath ? right : left;
    }

    /** 保留检索器或重排器的绝对分数，只做边界保护，避免低质量的一跳被抬到 1 分。 */
    static List<Evidence> boundHopScores(List<Evidence> evidences) {
        if (evidences == null || evidences.isEmpty()) return List.of();
        return evidences.stream()
                .map(e -> withScore(e, Math.max(0D, Math.min(1D, e.score()))))
                .toList();
    }

    /** @deprecated 查询校验已集中到 {@link RetrievalQueryGuard}。 */
    @Deprecated
    static String sanitizeFollowup(String original, String current, String candidate,
                                   Set<String> seenQueries) {
        return RetrievalQueryGuard.sanitize(original, current, candidate, seenQueries);
    }
    //辅助方法
    private static Evidence withScore(Evidence evidence, double score) {
        return new Evidence(
                evidence.nodeId(),
                evidence.nodeType(),
                evidence.chunkText(),
                score,
                evidence.graphPath(),
                evidence.sourceUrl(),
                evidence.sourceStatus(),
                evidence.contentHash()
        );
    }

    private Evidence mergeEvidence(Evidence oldEvidence,
                                   Evidence newEvidence) {
        return new Evidence(
                oldEvidence.nodeId(),
                oldEvidence.nodeType(),
                oldEvidence.chunkText(),
                oldEvidence.score() + newEvidence.score(),
                firstNonNull(
                        oldEvidence.graphPath(),
                        newEvidence.graphPath()
                ),
                firstNonNull(
                        oldEvidence.sourceUrl(),
                        newEvidence.sourceUrl()
                ),
                firstNonNull(oldEvidence.sourceStatus(), newEvidence.sourceStatus()),
                firstNonNull(oldEvidence.contentHash(), newEvidence.contentHash())
        );
    }

    private <T> T firstNonNull(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }

    /** 纯函数, 可单测: 解析 judge 输出, 失败返回 null */
    static JudgeDecision parse(String json) {
        try {
            JsonNode n = OBJECT_MAPPER.readTree(json);
            return new JudgeDecision(
                    n.path("sufficient").asBoolean(false),
                    readTextOrArray(n.path("followup_query")),
                    readTextOrArray(n.path("missing")));
        } catch (Exception e) {
            return null;
        }
    }

    private static String readTextOrArray(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            String value = node.asText(null);
            return value == null || value.isBlank() ? null : value;
        }
        if (!node.isArray()) {
            return null;
        }

        StringBuilder result = new StringBuilder();
        for (JsonNode item : node) {
            String value = readTextOrArray(item);
            if (value == null || value.isBlank()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(value.trim());
        }
        return result.length() == 0 ? null : result.toString();
    }

    public record JudgeDecision(boolean sufficient, String followupQuery, String missing) {}

    public record RetrievalResult(List<Evidence> evidences, int hops,
                                  boolean multiHopCandidate, String stopReason,
                                  FusedRetriever.RetrievalTelemetry telemetry) {
        public RetrievalResult {
            evidences = evidences == null ? List.of() : List.copyOf(evidences);
            hops = Math.max(0, hops);
            stopReason = stopReason == null ? "unknown" : stopReason;
            telemetry = telemetry == null ? FusedRetriever.RetrievalTelemetry.empty() : telemetry;
        }
    }

    /** Judge 字段缺失或非法时，用确定性规则生成下一跳查询，避免循环卡死。 */
    /** @deprecated 查询 fallback 已集中到 {@link RetrievalQueryGuard}。 */
    @Deprecated
    static String narrowFallback(String original, int hop) {
        return RetrievalQueryGuard.narrowFallback(original, hop);
    }

    /** @deprecated 查询 fallback 已集中到 {@link RetrievalQueryGuard}。 */
    @Deprecated
    static String missingFallback(String original, String missing) {
        return RetrievalQueryGuard.missingFallback(original, missing);
    }
}
