package com.tutor.retrieval.agentic;

import com.tutor.contract.Evidence;
import com.tutor.llm.RetrievalJudge;
import com.tutor.retrieval.GraphScope;
import com.tutor.retrieval.fusion.FusedRetriever;
import com.tutor.retrieval.graph.GraphExpansionPolicy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Agentic 多跳检索 (Phase 2 V4 2.1): 单跳证据不足时改写查询二次检索, 跳数上限 3。
 * 触发: 查询含"前置/从零/学习顺序/路径/先学/学完"等学习路径关键词。
 * 降级矩阵: judge 失败/解析失败 → 视为充分, 直接返回当前证据。
 */
@Component
public class AgenticRetriever {
    static final int MAX_HOPS = 3;
    private final FusedRetriever fusedRetriever;
    private final AgenticRetrievalLoop retrievalLoop;

    public AgenticRetriever(FusedRetriever fusedRetriever, RetrievalJudge judge) {
        this.fusedRetriever = fusedRetriever;
        this.retrievalLoop = new AgenticRetrievalLoop(fusedRetriever, judge);
    }

    public String retrievalProfileVersion() {
        return fusedRetriever.profileVersion();
    }

    public RetrievalResult retrieveAdaptiveResult(String query, int topK, String traceId,
                                                  boolean multiHopCandidate,
                                                  GraphExpansionPolicy graphPolicy,
                                                  GraphScope scope) {
        return retrievalLoop.retrieve(query, topK, traceId, multiHopCandidate, graphPolicy, scope);
    }

    static List<Evidence> boundHopScores(List<Evidence> evidences) {
        return AgenticRetrievalLoop.boundHopScores(evidences);
    }

    /** @deprecated Query validation is centralized in RetrievalQueryGuard. */
    @Deprecated
    static String sanitizeFollowup(String original, String current, String candidate,
                                   Set<String> seenQueries) {
        return RetrievalQueryGuard.sanitize(original, current, candidate, seenQueries);
    }

    static JudgeDecision parse(String json) {
        RetrievalJudgeOutputParser.Decision parsed = RetrievalJudgeOutputParser.parse(json);
        return parsed == null ? null
                : new JudgeDecision(parsed.sufficient(), parsed.followupQuery(), parsed.missing());
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
