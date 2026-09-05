package com.tutor.knowledge.retrieval.agentic;

import com.tutor.contract.Evidence;
import com.tutor.platform.llm.RetrievalJudge;
import com.tutor.knowledge.retrieval.GraphScope;
import com.tutor.knowledge.retrieval.fusion.FusedRetriever;
import com.tutor.knowledge.retrieval.graph.GraphExpansionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Executes the bounded multi-hop retrieval workflow; the facade owns only the public contract. */
final class AgenticRetrievalLoop {
    private static final Logger log = LoggerFactory.getLogger(AgenticRetrievalLoop.class);
    private static final int MAX_HOPS = 3;
    private static final double HOP_DECAY_FACTOR = 0.6D;

    private final FusedRetriever fusedRetriever;
    private final RetrievalJudge judge;

    AgenticRetrievalLoop(FusedRetriever fusedRetriever, RetrievalJudge judge) {
        this.fusedRetriever = fusedRetriever;
        this.judge = judge;
    }

    AgenticRetriever.RetrievalResult retrieve(String query, int topK, String traceId,
                                              boolean multiHopCandidate,
                                              GraphExpansionPolicy graphPolicy, GraphScope scope) {
        if (graphPolicy == null || scope == null) {
            throw new IllegalArgumentException("graphPolicy 鍜?scope 涓嶈兘涓虹┖");
        }
        if (!multiHopCandidate) {
            FusedRetriever.RetrievalOutcome outcome = retrieveOne(query, topK, traceId, graphPolicy, scope);
            return new AgenticRetriever.RetrievalResult(outcome.evidences(), 1,
                    false, "policy_single", outcome.telemetry());
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
            int hopTopK = Math.max(topK * 2, 10);
            FusedRetriever.RetrievalOutcome queryOutcome = retrieveOne(
                    currentQuery, hopTopK, traceId, graphPolicy, scope);
            telemetry = telemetry.plus(queryOutcome.telemetry());
            List<Evidence> queryResults = queryOutcome.evidences() == null
                    ? List.of() : queryOutcome.evidences();
            List<Evidence> graphResults = List.of();
            if (hop > 1 && !graphFrontier.isEmpty()) {
                try {
                    graphResults = fusedRetriever.expandFrontier(
                            graphFrontier, hopTopK, graphPolicy, scope);
                    telemetry = telemetry.plus(new FusedRetriever.RetrievalTelemetry(
                            0, 0, graphResults.size(), graphFrontier.size(),
                            false, false, false, false));
                } catch (RuntimeException error) {
                    log.warn("鍥惧墠娌挎墿灞曞け璐ワ紝淇濈暀鏌ヨ妫€绱㈢粨鏋?hop={} trace={}", hop, traceId);
                }
            }

            List<Evidence> hopResults = boundHopScores(
                    mergeHopResults(queryResults, graphResults, hopTopK));
            if (hopResults.isEmpty()) {
                stopReason = "no_evidence";
                break;
            }
            double hopWeight = Math.pow(HOP_DECAY_FACTOR, hop - 1);
            for (Evidence evidence : hopResults) {
                Evidence weighted = withScore(evidence, evidence.score() * hopWeight);
                byId.merge(weighted.nodeId(), weighted, AgenticRetrievalLoop::mergeEvidence);
            }

            graphFrontier = hop == 1
                    ? queryResults.stream()
                    .filter(evidence -> evidence.graphPath() != null
                            && !evidence.graphPath().isBlank()).toList()
                    : graphResults;

            if (hop == MAX_HOPS) {
                stopReason = "max_hops";
                break;
            }

            String verdict;
            try {
                List<Evidence> strongestEvidence = byId.values().stream()
                        .sorted(java.util.Comparator.comparingDouble(Evidence::score).reversed()
                                .thenComparing(Evidence::nodeId,
                                        java.util.Comparator.nullsLast(String::compareTo)))
                        .limit(8)
                        .toList();
                verdict = judge.judgeSufficientWithEvidence(
                        query, currentQuery, strongestEvidence, traceId);
            } catch (Exception error) {
                log.warn("judge 澶辫触, 鍋滄澶氳烦骞朵繚鐣欏凡鏈夎瘉鎹?hop={} trace={}", hop, traceId);
                stopReason = "judge_failure";
                break;
            }

            RetrievalJudgeOutputParser.Decision decision = RetrievalJudgeOutputParser.parse(verdict);
            if (decision == null) {
                log.warn("judge 杩斿洖闈炴硶 JSON, 鍋滄澶氳烦骞朵繚鐣欏凡鏈夎瘉鎹?hop={} trace={}", hop, traceId);
                stopReason = "judge_invalid";
                break;
            }
            if (decision.sufficient()) {
                stopReason = "judge_sufficient";
                break;
            }

            String candidate = RetrievalQueryGuard.sanitize(
                    query, currentQuery, decision.followupQuery(), seenQueries);
            if (candidate == null) {
                candidate = RetrievalQueryGuard.sanitize(query, currentQuery,
                        RetrievalQueryGuard.missingFallback(query, decision.missing()), seenQueries);
            }
            if (candidate == null) {
                candidate = RetrievalQueryGuard.sanitize(query, currentQuery,
                        RetrievalQueryGuard.narrowFallback(query, hop), seenQueries);
            }
            if (candidate == null) {
                stopReason = "followup_invalid";
                break;
            }
            currentQuery = candidate;
            seenQueries.add(RetrievalQueryGuard.normalizeQuery(currentQuery));
            log.info("澶氳烦 hop={} 鏀瑰啓鏌ヨ: {} 鈫?{}", hop, query, currentQuery);
        }

        List<Evidence> results = byId.values().stream()
                .sorted(java.util.Comparator.comparingDouble(Evidence::score).reversed()
                        .thenComparing(Evidence::nodeId,
                                java.util.Comparator.nullsLast(String::compareTo)))
                .limit(topK)
                .toList();
        return new AgenticRetriever.RetrievalResult(results, hops, true, stopReason, telemetry);
    }

    private FusedRetriever.RetrievalOutcome retrieveOne(String query, int topK, String traceId,
                                                         GraphExpansionPolicy graphPolicy, GraphScope scope) {
        return fusedRetriever.retrieve(query, topK, traceId, true, true, graphPolicy, scope);
    }

    static List<Evidence> boundHopScores(List<Evidence> evidences) {
        if (evidences == null || evidences.isEmpty()) return List.of();
        return evidences.stream()
                .map(evidence -> withScore(evidence,
                        Math.max(0D, Math.min(1D, evidence.score()))))
                .toList();
    }

    private static List<Evidence> mergeHopResults(List<Evidence> queryResults,
                                                   List<Evidence> graphResults, int limit) {
        Map<String, Evidence> merged = new LinkedHashMap<>();
        if (queryResults != null) {
            queryResults.stream().filter(evidence -> evidence != null && evidence.nodeId() != null)
                    .forEach(evidence -> merged.merge(evidence.nodeId(), evidence,
                            AgenticRetrievalLoop::preferEvidence));
        }
        if (graphResults != null) {
            graphResults.stream().filter(evidence -> evidence != null && evidence.nodeId() != null)
                    .forEach(evidence -> merged.merge(evidence.nodeId(), evidence,
                            AgenticRetrievalLoop::preferEvidence));
        }
        return merged.values().stream()
                .sorted(java.util.Comparator.comparingDouble(Evidence::score).reversed()
                        .thenComparing(Evidence::nodeId,
                                java.util.Comparator.nullsLast(String::compareTo)))
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

    private static Evidence mergeEvidence(Evidence oldEvidence, Evidence newEvidence) {
        return new Evidence(oldEvidence.nodeId(), oldEvidence.nodeType(), oldEvidence.chunkText(),
                oldEvidence.score() + newEvidence.score(),
                firstNonNull(oldEvidence.graphPath(), newEvidence.graphPath()),
                firstNonNull(oldEvidence.sourceUrl(), newEvidence.sourceUrl()),
                firstNonNull(oldEvidence.sourceStatus(), newEvidence.sourceStatus()),
                firstNonNull(oldEvidence.contentHash(), newEvidence.contentHash()));
    }

    private static Evidence withScore(Evidence evidence, double score) {
        return new Evidence(evidence.nodeId(), evidence.nodeType(), evidence.chunkText(), score,
                evidence.graphPath(), evidence.sourceUrl(), evidence.sourceStatus(), evidence.contentHash());
    }

    private static <T> T firstNonNull(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }
}
