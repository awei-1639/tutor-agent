package com.tutor.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.Intent;
import com.tutor.expert.IntentRouter.RetrievalHint;
import com.tutor.expert.IntentRouter.RouteDecision;
import com.tutor.expert.IntentRouter.Scope;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parses and validates the structured intent decision returned by the router. */
final class IntentDecisionParser {
    private IntentDecisionParser() {
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

}
