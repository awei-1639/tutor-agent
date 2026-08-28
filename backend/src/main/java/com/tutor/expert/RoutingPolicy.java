package com.tutor.expert;

import com.tutor.contract.Intent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 将 LLM 的路由建议转换为有边界、确定性的执行计划。 */
@Component
public class RoutingPolicy {
    /** 跳过检索影响较大，因此需要采用保守阈值。 */
    static final double DEFAULT_OUT_OF_SCOPE_THRESHOLD = 0.92D;
    static final double DEFAULT_MULTI_HOP_CONFIDENCE_THRESHOLD = 0.70D;
    static final double DEFAULT_CLARIFICATION_CONFIDENCE_THRESHOLD = 0.80D;
    static final double DEFAULT_INTENT_MARGIN_THRESHOLD = 0.15D;
    private final double outOfScopeThreshold;
    private final double multiHopConfidenceThreshold;
    private final double clarificationConfidenceThreshold;
    private final double intentMarginThreshold;
    private static final Pattern DOMAIN_SIGNAL = Pattern.compile(
            "简历|面试|岗位|求职|学习|课程|技能|算法|模型|编程|开发|RAG|Transformer|Python|大模型|AI|人工智能|知识库|投递|招聘|职业",
            Pattern.CASE_INSENSITIVE);

    public RoutingPolicy() {
        this(DEFAULT_OUT_OF_SCOPE_THRESHOLD, DEFAULT_MULTI_HOP_CONFIDENCE_THRESHOLD,
                DEFAULT_CLARIFICATION_CONFIDENCE_THRESHOLD, DEFAULT_INTENT_MARGIN_THRESHOLD);
    }

    public RoutingPolicy(@Value("${tutor.routing.out-of-scope-threshold:0.92}") double outOfScopeThreshold) {
        this(outOfScopeThreshold, DEFAULT_MULTI_HOP_CONFIDENCE_THRESHOLD,
                DEFAULT_CLARIFICATION_CONFIDENCE_THRESHOLD, DEFAULT_INTENT_MARGIN_THRESHOLD);
    }

    @Autowired
    public RoutingPolicy(
            @Value("${tutor.routing.out-of-scope-threshold:0.92}") double outOfScopeThreshold,
            @Value("${tutor.routing.multi-hop-confidence-threshold:0.70}") double multiHopConfidenceThreshold,
            @Value("${tutor.routing.clarification-confidence-threshold:0.80}") double clarificationConfidenceThreshold,
            @Value("${tutor.routing.intent-margin-threshold:0.15}") double intentMarginThreshold) {
        if (!Double.isFinite(outOfScopeThreshold) || outOfScopeThreshold < 0D || outOfScopeThreshold > 1D) {
            throw new IllegalArgumentException("tutor.routing.out-of-scope-threshold must be between 0 and 1");
        }
        if (!Double.isFinite(multiHopConfidenceThreshold)
                || multiHopConfidenceThreshold < 0D || multiHopConfidenceThreshold > 1D) {
            throw new IllegalArgumentException("tutor.routing.multi-hop-confidence-threshold must be between 0 and 1");
        }
        if (!Double.isFinite(clarificationConfidenceThreshold)
                || clarificationConfidenceThreshold < 0D || clarificationConfidenceThreshold > 1D) {
            throw new IllegalArgumentException("tutor.routing.clarification-confidence-threshold must be between 0 and 1");
        }
        if (!Double.isFinite(intentMarginThreshold) || intentMarginThreshold < 0D || intentMarginThreshold > 1D) {
            throw new IllegalArgumentException("tutor.routing.intent-margin-threshold must be between 0 and 1");
        }
        this.outOfScopeThreshold = outOfScopeThreshold;
        this.multiHopConfidenceThreshold = multiHopConfidenceThreshold;
        this.clarificationConfidenceThreshold = clarificationConfidenceThreshold;
        this.intentMarginThreshold = intentMarginThreshold;
    }

    /** 中等置信度且存在竞争意图时先澄清，避免用猜测驱动检索和专家扇出。 */
    public boolean shouldClarify(IntentRouter.RouteDecision decision) {
        if (decision == null || decision.scope() != IntentRouter.Scope.IN_SCOPE || decision.degraded()) {
            return false;
        }
        if (decision.confidence() >= clarificationConfidenceThreshold) {
            return hasNarrowIntentMargin(decision);
        }
        return hasNarrowIntentMargin(decision)
                || decision.reasonCodes().contains("MODEL_AMBIGUITY")
                || decision.reasonCodes().contains("MODEL_COMPETING_INTENT");
    }

    public String clarificationQuestion(IntentRouter.RouteDecision decision) {
        if (decision != null && decision.intent() == Intent.PLANNING) {
            return "你更希望我先制定学习计划，还是先分析目标岗位和简历匹配度？";
        }
        if (decision != null && decision.intent() == Intent.RESUME) {
            return "你更希望我先优化简历，还是先制定针对目标岗位的学习计划？";
        }
        return "你更希望我优先处理简历、面试，还是学习规划？";
    }

    public List<Map<String, String>> clarificationOptions(IntentRouter.RouteDecision decision) {
        if (decision != null && decision.intent() == Intent.PLANNING) {
            return List.of(Map.of("id", "planning", "label", "先制定学习计划"),
                    Map.of("id", "resume", "label", "先分析岗位和简历匹配度"));
        }
        if (decision != null && decision.intent() == Intent.RESUME) {
            return List.of(Map.of("id", "resume", "label", "先优化简历"),
                    Map.of("id", "planning", "label", "先制定岗位学习计划"));
        }
        return List.of(Map.of("id", "resume", "label", "简历"),
                Map.of("id", "interview", "label", "面试"),
                Map.of("id", "planning", "label", "学习规划"));
    }

    public ExecutionPlan plan(IntentRouter.RouteDecision decision) {
        return plan(decision, null);
    }

    /** 在允许高影响的跳过检索前，执行严格的领域边界校验。 */
    public ExecutionPlan plan(IntentRouter.RouteDecision decision, String question) {
        decision = calibrateBoundary(decision, question);
        if (decision == null) return fallback("ROUTE_DECISION_MISSING");

        if (decision.scope() == IntentRouter.Scope.OUT_OF_SCOPE
                && decision.calibratedConfidence() != null
                && decision.calibratedConfidence() >= outOfScopeThreshold
                && !decision.degraded()) {
            return new ExecutionPlan(Intent.OUT_OF_SCOPE, List.of(), List.of(), IntentRouter.RetrievalHint.NONE,
                    false, true, false, decision.reasonCodes());
        }

        // 不确定或低置信度的越界请求仍走安全的领域内路径；相比静默丢弃
        // 合法的学习/职业问题，一次低成本的单跳检索更可靠。
        boolean trustedInScope = decision.scope() == IntentRouter.Scope.IN_SCOPE && !decision.degraded();
        Intent effectiveIntent = trustedInScope && decision.intent() != Intent.OUT_OF_SCOPE
                ? decision.intent() : Intent.CHAT;
        List<Intent> effectiveIntents = trustedInScope && !decision.subIntents().isEmpty()
                ? decision.subIntents() : (effectiveIntent == Intent.CHAT ? List.of() : List.of(effectiveIntent));
        // 多跳会显著增加检索和 LLM 成本；低置信度时先做一次便宜的单跳，
        // 避免把不确定的路由建议放大成昂贵的执行计划。
        boolean allowMultiHop = trustedInScope
                && decision.confidence() >= multiHopConfidenceThreshold
                && !decision.reasonCodes().contains("MODEL_AMBIGUITY")
                && !decision.reasonCodes().contains("MODEL_COMPETING_INTENT")
                && !hasNarrowIntentMargin(decision)
                && decision.retrievalHint() == IntentRouter.RetrievalHint.MULTI_CANDIDATE;
        IntentRouter.RetrievalHint hint = allowMultiHop
                ? IntentRouter.RetrievalHint.MULTI_CANDIDATE : IntentRouter.RetrievalHint.SINGLE;
        return new ExecutionPlan(effectiveIntent, effectiveIntents,
                trustedInScope ? decision.retrievalFacets() : List.of(), hint,
                hint == IntentRouter.RetrievalHint.MULTI_CANDIDATE,
                false, decision.degraded(), decision.reasonCodes());
    }

    private boolean hasNarrowIntentMargin(IntentRouter.RouteDecision decision) {
        return decision.alternativeConfidence() != null
                && Math.abs(decision.confidence() - decision.alternativeConfidence()) < intentMarginThreshold;
    }

    private IntentRouter.RouteDecision calibrateBoundary(IntentRouter.RouteDecision decision, String question) {
        if (decision == null || question == null || question.isBlank()
                || decision.scope() != IntentRouter.Scope.OUT_OF_SCOPE
                || !DOMAIN_SIGNAL.matcher(question).find()) {
            return decision;
        }
        List<String> reasons = new ArrayList<>(decision.reasonCodes());
        reasons.add("DOMAIN_BOUNDARY_SIGNAL");
        return new IntentRouter.RouteDecision(IntentRouter.Scope.UNCERTAIN, Intent.CHAT, List.of(),
                List.of(), IntentRouter.RetrievalHint.SINGLE, Math.min(decision.confidence(), 0.20D),
                null, reasons, decision.degraded());
    }

    private ExecutionPlan fallback(String reason) {
        return new ExecutionPlan(Intent.CHAT, List.of(), List.of(), IntentRouter.RetrievalHint.SINGLE,
                false, false, true, List.of(reason));
    }

    public enum RetrievalFacet { LEARNING, CAREER, RESOURCE }

    public record ExecutionPlan(Intent intent, List<Intent> intents, List<RetrievalFacet> retrievalFacets,
                                IntentRouter.RetrievalHint retrievalHint,
                                boolean allowMultiHopEscalation, boolean skipRetrieval,
                                boolean degraded, List<String> reasonCodes) {
        public ExecutionPlan {
            intent = intent == null ? Intent.CHAT : intent;
            intents = intents == null ? List.of() : intents.stream().filter(i -> i != null).distinct().toList();
            retrievalFacets = retrievalFacets == null ? List.of()
                    : retrievalFacets.stream().filter(facet -> facet != null).distinct().toList();
            retrievalHint = retrievalHint == null ? IntentRouter.RetrievalHint.SINGLE : retrievalHint;
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        }
    }
}
