package com.tutor.expert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.tutor.contract.Intent;
import com.tutor.contract.Purpose;
import com.tutor.llm.JsonGenerationGateway;
import com.tutor.llm.structured.RouterOutput;
import com.tutor.llm.structured.StructuredOutputResult;
import com.tutor.llm.structured.StructuredOutputService;
import com.tutor.llm.structured.StructuredTask;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
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
            - retrieval_facets 只可包含 career、learning、resource：career 用于岗位/投递/简历匹配，learning 用于学习前置与路径，resource 用于找课程/资料/题库。
            - in_scope 请求必须输出 retrieval_facets（没有图检索语义时输出 []）；out_of_scope 必须输出 []。
            scope 表示是否属于产品领域；retrieval_hint 只是检索建议，不是最终执行决定。
            confidence 表示“当前意图标签正确”的自评概率，不是答案质量或经过校准的准确率。
            只有证据充分、没有合理竞争意图时才可给出 0.90 以上；存在歧义时应降低分数。
            alternative_intent 填最有竞争力的第二意图，没有则填 none；存在歧义时必须填写 ambiguity_flags。
            alternative_confidence 表示第二意图的自评概率；没有第二意图时填 0。
            不要因为高 confidence 直接承诺结果或跳过检索。
            只输出JSON。
            """;

    private final JsonGenerationGateway gateway;
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
        this.gateway = gateway;
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
                    SystemMessage.from(SYS),
                    UserMessage.from(context + "当前请求: " + question)),
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
        List<String> issues = new ArrayList<>();
        try {
            JsonNode node = mapper.readTree(json);
            if (node == null || !node.isObject()) {
                return RouteDecision.degraded("ROUTE_RESPONSE_NOT_OBJECT");
            }
            String rawScope = requiredText(node, "scope", issues);
            String rawIntent = requiredText(node, "intent", issues);
            String rawHint = requiredText(node, "retrieval_hint", issues);
            Scope scope = parseScope(rawScope, issues);
            Intent intent = parseIntentValue(rawIntent, "INTENT", issues);
            RetrievalHint hint = parseHint(rawHint, issues);
            double confidence = parseConfidence(node, issues);
            Double alternativeConfidence = parseOptionalConfidence(node, "alternative_confidence", issues);
            List<Intent> subIntents = parseSubIntents(node, intent, issues);
            List<RoutingPolicy.RetrievalFacet> retrievalFacets = parseRetrievalFacets(node, scope, issues);
            validateConsistency(scope, intent, hint, subIntents, retrievalFacets, issues);
            List<String> modelReasons = readModelReasons(node, issues);
            if (hasCompetingAlternative(node, intent, issues)) {
                modelReasons.add("MODEL_COMPETING_INTENT");
                if (alternativeConfidence != null && Math.abs(confidence - alternativeConfidence) < 0.15D) {
                    modelReasons.add("MODEL_CLOSE_INTENTS");
                }
            }
            if (hasAmbiguityFlags(node, issues)) {
                modelReasons.add("MODEL_AMBIGUITY");
            }
            List<String> reasonCodes = new ArrayList<>(modelReasons);
            reasonCodes.addAll(issues);
            return new RouteDecision(scope, intent, subIntents, retrievalFacets, hint, confidence, null,
                    alternativeConfidence,
                    List.copyOf(new LinkedHashSet<>(reasonCodes)), !issues.isEmpty());
        } catch (Exception e) {
            return RouteDecision.degraded("ROUTER_INVALID_JSON");
        }
    }

    private static String requiredText(JsonNode node, String field, List<String> issues) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            issues.add("MISSING_" + field.toUpperCase(Locale.ROOT));
            return "";
        }
        return value.asText().trim();
    }

    private static double parseConfidence(JsonNode node, List<String> issues) {
        JsonNode value = node.get("confidence");
        if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble())) {
            issues.add("INVALID_CONFIDENCE");
            return 0D;
        }
        double confidence = value.asDouble();
        if (confidence < 0D || confidence > 1D) {
            issues.add("CONFIDENCE_OUT_OF_RANGE");
            return Math.max(0D, Math.min(1D, confidence));
        }
        return confidence;
    }

    private static Double parseOptionalConfidence(JsonNode node, String field, List<String> issues) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
            issues.add("INVALID_" + field.toUpperCase(Locale.ROOT));
            return null;
        }
        double confidence = value.asDouble();
        if (confidence < 0D || confidence > 1D) {
            issues.add("" + field.toUpperCase(Locale.ROOT) + "_OUT_OF_RANGE");
            return Math.max(0D, Math.min(1D, confidence));
        }
        return confidence;
    }

    private static List<String> readModelReasons(JsonNode node, List<String> issues) {
        List<String> modelReasons = new ArrayList<>();
        JsonNode reasons = node.get("reason_codes");
        if (reasons == null) return modelReasons;
        if (!reasons.isArray()) {
            issues.add("INVALID_REASON_CODES");
            return modelReasons;
        }
        reasons.forEach(item -> {
            if (!item.isTextual() || item.asText().isBlank()) issues.add("INVALID_REASON_CODE");
            else modelReasons.add(item.asText().trim());
        });
        return modelReasons;
    }

    /** 歧义是软信号：不拒绝请求，但会阻止 RoutingPolicy 直接升级到多跳。 */
    private static boolean hasAmbiguityFlags(JsonNode node, List<String> issues) {
        JsonNode flags = node.get("ambiguity_flags");
        if (flags == null || flags.isNull()) return false;
        if (!flags.isArray()) {
            issues.add("INVALID_AMBIGUITY_FLAGS");
            return false;
        }
        boolean present = false;
        for (JsonNode flag : flags) {
            if (!flag.isTextual() || flag.asText().isBlank()) {
                issues.add("INVALID_AMBIGUITY_FLAG");
            } else {
                present = true;
            }
        }
        return present;
    }

    /** 第二候选意图是结构化的竞争信号；不把模型原文写入 trace，避免污染可观测字段。 */
    private static boolean hasCompetingAlternative(JsonNode node, Intent primary, List<String> issues) {
        JsonNode alternative = node.get("alternative_intent");
        if (alternative == null || alternative.isNull()
                || (alternative.isTextual() && alternative.asText().trim().equalsIgnoreCase("none"))) {
            return false;
        }
        if (!alternative.isTextual() || alternative.asText().isBlank()) {
            issues.add("INVALID_ALTERNATIVE_INTENT");
            return false;
        }
        try {
            Intent candidate = Intent.valueOf(alternative.asText().trim().toUpperCase(Locale.ROOT));
            return candidate != primary;
        } catch (Exception ignored) {
            issues.add("UNKNOWN_ALTERNATIVE_INTENT");
            return false;
        }
    }

    private static List<Intent> parseSubIntents(JsonNode node, Intent intent, List<String> issues) {
        if (intent != Intent.MIXED) {
            return intent == Intent.OUT_OF_SCOPE ? List.of() : List.of(intent);
        }
        JsonNode values = node.get("intents");
        if (values == null || !values.isArray() || values.isEmpty()) {
            issues.add("MIXED_SUBINTENTS_REQUIRED");
            return List.of();
        }
        Set<Intent> parsed = new LinkedHashSet<>();
        values.forEach(value -> {
            if (!value.isTextual()) {
                issues.add("INVALID_SUB_INTENT");
                return;
            }
            Intent subIntent = parseIntentValue(value.asText(), "SUB_INTENT", issues);
            if (subIntent == Intent.RESUME || subIntent == Intent.INTERVIEW || subIntent == Intent.PLANNING) {
                parsed.add(subIntent);
            } else {
                issues.add("INVALID_MIXED_SUB_INTENT");
            }
        });
        if (parsed.size() < 2) issues.add("MIXED_SUBINTENTS_INCOMPLETE");
        return List.copyOf(parsed);
    }

    private static List<RoutingPolicy.RetrievalFacet> parseRetrievalFacets(JsonNode node, Scope scope,
                                                                             List<String> issues) {
        JsonNode values = node.get("retrieval_facets");
        if (values == null || !values.isArray()) {
            issues.add("MISSING_RETRIEVAL_FACETS");
            return List.of();
        }
        LinkedHashSet<RoutingPolicy.RetrievalFacet> facets = new LinkedHashSet<>();
        values.forEach(value -> {
            if (!value.isTextual()) {
                issues.add("INVALID_RETRIEVAL_FACET");
                return;
            }
            try {
                facets.add(RoutingPolicy.RetrievalFacet.valueOf(value.asText().trim().toUpperCase(Locale.ROOT)));
            } catch (Exception ignored) {
                issues.add("UNKNOWN_RETRIEVAL_FACET");
            }
        });
        if (scope == Scope.OUT_OF_SCOPE && !facets.isEmpty()) issues.add("OUT_OF_SCOPE_FACETS_FORBIDDEN");
        return List.copyOf(facets);
    }

    private static void validateConsistency(Scope scope, Intent intent, RetrievalHint hint,
                                            List<Intent> subIntents,
                                            List<RoutingPolicy.RetrievalFacet> retrievalFacets,
                                            List<String> issues) {
        if (scope == Scope.OUT_OF_SCOPE && (intent != Intent.OUT_OF_SCOPE || hint != RetrievalHint.NONE)) {
            issues.add("ROUTE_SCOPE_INTENT_CONFLICT");
        }
        if (scope == Scope.IN_SCOPE && intent == Intent.OUT_OF_SCOPE) {
            issues.add("ROUTE_SCOPE_INTENT_CONFLICT");
        }
        if (scope != Scope.IN_SCOPE && hint == RetrievalHint.MULTI_CANDIDATE) {
            issues.add("ROUTE_SCOPE_HINT_CONFLICT");
        }
        if (intent == Intent.MIXED && subIntents.isEmpty()) {
            issues.add("MIXED_SUBINTENTS_REQUIRED");
        }
        if (scope == Scope.OUT_OF_SCOPE && retrievalFacets != null && !retrievalFacets.isEmpty()) {
            issues.add("OUT_OF_SCOPE_FACETS_FORBIDDEN");
        }
    }

    private static Intent parseIntentValue(String value, String field, List<String> issues) {
        try {
            return Intent.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            issues.add("UNKNOWN_" + field);
            return Intent.CHAT;
        }
    }

    private static Scope parseScope(String value, List<String> issues) {
        try {
            return Scope.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            issues.add("UNKNOWN_SCOPE");
            return Scope.UNCERTAIN;
        }
    }

    private static RetrievalHint parseHint(String value, List<String> issues) {
        try {
            return RetrievalHint.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            issues.add("UNKNOWN_RETRIEVAL_HINT");
            return RetrievalHint.SINGLE;
        }
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
