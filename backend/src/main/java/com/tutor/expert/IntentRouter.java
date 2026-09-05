package com.tutor.expert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.Intent;
import com.tutor.contract.Purpose;
import com.tutor.llm.JsonGenerationGateway;
import com.tutor.llm.structured.RouterOutput;
import com.tutor.llm.structured.StructuredOutputResult;
import com.tutor.llm.structured.StructuredOutputService;
import com.tutor.llm.structured.StructuredTask;
import com.tutor.llm.LlmMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 意图路由 (V3 3.2): 最小 context（枚举定义+最近用户消息；指代追问时增加主题锚点），轻量调用。
 * 降级矩阵: provider/预算失败 → CHAT + 单跳检索；只有高置信度越界才跳过检索。
 */
@Component
public class IntentRouter {
    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);
    private static final String SYS = """
            你是意图分类器。输出JSON，不要输出额外文本:
            {"scope":"in_scope|out_of_scope|uncertain","intent":"...","intents":["resume","interview"],"alternative_intent":"...|none","alternative_confidence":0.0,"ambiguity_flags":[],"retrieval_facets":["learning"],"retrieval_hint":"none|single|multi_candidate","confidence":0.0,"reason_codes":[]}
            - resume: 简历内容优化、简历与岗位匹配度、投递建议
            - interview: 面试题、笔试题、面试准备与模拟
            - planning: 学习路径、学习计划、技能提升规划
            - mixed: 同时涉及上述两类及以上的综合请求；此时 intents 必须列出具体子意图，不要填 mixed
            - chat: 与学习求职相关的一般咨询问答 (概念解释、岗位信息查询等)
            - out_of_scope: 与AI学习/求职完全无关的请求
            - retrieval_facets 只可包含 career、learning、resource：career 用于岗位要求/投递/简历匹配，learning 用于学习前置与路径（技能先后依赖），resource 用于找课程/资料/题库。
            - 叠加 facet 必须带来新的图关系，否则只给一个：learning 已含资源扩展，叠加 resource 仅当问题主体就是资料本身；career+learning 仅当同时要求「岗位需要什么」与「技能怎么排先后」。
            - 纯概念解释（什么是X、X与Y的区别）和沟通话术（如何回答/如何表达）不依赖图关系，输出 []。
            - 模拟面试/笔试出题按知识点覆盖处理：给 learning 不给 career，岗位名词只是语境；问岗位要求/匹配度才给 career。
            - in_scope 请求必须输出 retrieval_facets（没有图检索语义时输出 []）；out_of_scope 必须输出 []。
            scope 表示是否属于产品领域；retrieval_hint 只是检索建议，不是最终执行决定。
            confidence 表示“当前意图标签正确”的自评概率，不是答案质量或经过校准的准确率。
            只有证据充分、没有合理竞争意图时才可给出 0.90 以上；存在歧义时应降低分数。
            alternative_intent 填最有竞争力的第二意图，没有则填 none；存在歧义时必须填写 ambiguity_flags。
            alternative_confidence 表示第二意图的自评概率；没有第二意图时填 0。
            不要因为高 confidence 直接承诺结果或跳过检索。
            只输出JSON。
            """;

    private final RoutingConfidenceCalibrator confidenceCalibrator;
    private final StructuredOutputService structuredOutputService;
    private final ObjectMapper mapper = new ObjectMapper();

    public IntentRouter(JsonGenerationGateway gateway) {
        this(gateway, new RoutingConfidenceCalibrator(false, null),
                new StructuredOutputService(gateway, null));
    }

    public IntentRouter(JsonGenerationGateway gateway, RoutingConfidenceCalibrator confidenceCalibrator) {
        this(gateway, confidenceCalibrator, new StructuredOutputService(gateway, null));
    }

    @Autowired
    public IntentRouter(JsonGenerationGateway gateway,
                        RoutingConfidenceCalibrator confidenceCalibrator,
                        StructuredOutputService structuredOutputService) {
        this.confidenceCalibrator = confidenceCalibrator;
        this.structuredOutputService = structuredOutputService;
    }

    // 调用 LLM 判断意图和检索提示。
    public RouteDecision routeDecision(String question, List<String> recentUserMessages, String traceId) {
        try {
            String context = recentUserMessages.isEmpty() ? ""
                    : "此前用户消息: " + String.join(" / ", recentUserMessages) + "\n";
            StructuredOutputResult<RouterOutput> structured = structuredOutputService.generate(
                    StructuredTask.ROUTER,
                    Purpose.ROUTER,
                    List.of(
                    LlmMessage.system(SYS),
                    LlmMessage.user(context + "当前请求: " + question)),
                    RouterOutput.class,
                    null,
                    traceId
            );
            if (!structured.success()) {
                return RouteDecision.degraded("ROUTER_INVALID_JSON");
            }
            String json = mapper.writeValueAsString(structured.value());
            return applyCalibration(parseDecision(json, mapper));
        } catch (Exception e) {
            log.warn("router不可用, 降级CHAT+SINGLE trace={} type={}", traceId, e.getClass().getSimpleName());
            return RouteDecision.degraded("ROUTER_UNAVAILABLE");
        }
    }

    private RouteDecision applyCalibration(RouteDecision decision) {
        RoutingConfidenceCalibrator.CalibrationResult result = confidenceCalibrator.calibrate(decision);
        if (result.confidence() == null && !result.degraded() && result.reasonCodes().isEmpty()) {
            return decision;
        }
        List<String> reasons = new ArrayList<>(decision.reasonCodes());
        reasons.addAll(result.reasonCodes());
        return new RouteDecision(decision.scope(), decision.intent(), decision.subIntents(), decision.retrievalFacets(),
                decision.retrievalHint(), decision.confidence(), result.confidence(), decision.alternativeConfidence(),
                List.copyOf(new LinkedHashSet<>(reasons)), decision.degraded() || result.degraded());
    }

    static RouteDecision parseDecision(String json, ObjectMapper mapper) {
        return IntentDecisionParser.parseDecision(json, mapper);
    }

    public enum Scope { IN_SCOPE, OUT_OF_SCOPE, UNCERTAIN }
    public enum RetrievalHint { NONE, SINGLE, MULTI_CANDIDATE }

    public record RouteDecision(Scope scope, Intent intent, List<Intent> subIntents,
                                List<RoutingPolicy.RetrievalFacet> retrievalFacets,
                                RetrievalHint retrievalHint, double confidence,
                                Double calibratedConfidence, Double alternativeConfidence,
                                List<String> reasonCodes, boolean degraded) {
        public RouteDecision(Scope scope, Intent intent, List<Intent> subIntents,
                             List<RoutingPolicy.RetrievalFacet> retrievalFacets,
                             RetrievalHint retrievalHint, double confidence,
                             Double calibratedConfidence, List<String> reasonCodes, boolean degraded) {
            this(scope, intent, subIntents, retrievalFacets, retrievalHint, confidence,
                    calibratedConfidence, null, reasonCodes, degraded);
        }

        public RouteDecision {
            scope = scope == null ? Scope.UNCERTAIN : scope;
            intent = intent == null ? Intent.CHAT : intent;
            subIntents = subIntents == null ? List.of()
                    : subIntents.stream().filter(i -> i != null).distinct().toList();
            retrievalFacets = retrievalFacets == null ? List.of() : retrievalFacets.stream()
                    .filter(facet -> facet != null).distinct().toList();
            if (subIntents.isEmpty() && intent != Intent.MIXED && intent != Intent.OUT_OF_SCOPE
                    && scope == Scope.IN_SCOPE) {
                subIntents = List.of(intent);
            }
            retrievalHint = retrievalHint == null ? RetrievalHint.SINGLE : retrievalHint;
            List<String> normalizedReasons = reasonCodes == null ? new ArrayList<>() : new ArrayList<>(reasonCodes);
            boolean validMixedSubIntents = intent != Intent.MIXED
                    || subIntents.size() >= 2
                    && subIntents.stream().allMatch(i -> i == Intent.RESUME
                    || i == Intent.INTERVIEW || i == Intent.PLANNING);
            if (!validMixedSubIntents) {
                normalizedReasons.add("MIXED_SUBINTENTS_REQUIRED");
                degraded = true;
            }
            if (scope == Scope.OUT_OF_SCOPE && !retrievalFacets.isEmpty()) {
                normalizedReasons.add("OUT_OF_SCOPE_FACETS_FORBIDDEN");
                retrievalFacets = List.of();
                degraded = true;
            }
            if (!Double.isFinite(confidence)) {
                normalizedReasons.add("INVALID_CONFIDENCE");
                degraded = true;
            } else if (confidence < 0D || confidence > 1D) {
                normalizedReasons.add("CONFIDENCE_OUT_OF_RANGE");
                degraded = true;
            }
            reasonCodes = List.copyOf(new LinkedHashSet<>(normalizedReasons));
            confidence = Double.isFinite(confidence) ? Math.max(0D, Math.min(1D, confidence)) : 0D;
            if (calibratedConfidence != null && (!Double.isFinite(calibratedConfidence)
                    || calibratedConfidence < 0D || calibratedConfidence > 1D)) {
                calibratedConfidence = null;
            }
            if (alternativeConfidence != null && (!Double.isFinite(alternativeConfidence)
                    || alternativeConfidence < 0D || alternativeConfidence > 1D)) {
                normalizedReasons.add("INVALID_ALTERNATIVE_CONFIDENCE");
                alternativeConfidence = null;
                degraded = true;
            }
            reasonCodes = List.copyOf(new LinkedHashSet<>(normalizedReasons));
        }

        static RouteDecision degraded(String reason) {
            return new RouteDecision(Scope.UNCERTAIN, Intent.CHAT, List.of(), List.of(), RetrievalHint.SINGLE,
                    0D, null, List.of(reason), true);
        }
    }
}
