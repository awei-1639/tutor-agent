package com.tutor.knowledge.retrieval.agentic;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agentic 检索子查询的统一安全边界。
 *
 * 这里处理的是“证据缺口驱动的下一跳查询”，不是用户输入阶段的上下文改写。
 * 负责清洗、长度限制、去重、主题漂移保护以及确定性 fallback。
 */
public final class RetrievalQueryGuard {
    public static final int MAX_FOLLOWUP_CHARS = 240;
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\u0000-\\u001f\\u007f]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TERM_PUNCTUATION = Pattern.compile(
            "[\\p{Punct}，。！？、；：‘’“”《》【】（）]");
    private static final Pattern TERMS = Pattern.compile("[\\p{L}\\p{N}]{2,}");
    private static final Pattern CORE_TOPIC_NOISE = Pattern.compile(
            "(?i)(零基础|想学|需要|哪些|怎么|如何|什么是|\\?|？|，|,)");
    private static final Set<String> STOP_TERMS = Set.of(
            "什么", "如何", "哪些", "怎么", "需要", "想学");
    private static final String[] FALLBACK_HINTS = {
            "前置知识 基础概念",
            "依赖关系 底层原理"
    };

    private RetrievalQueryGuard() {
    }

    public static String sanitize(
            String original,
            String current,
            String candidate,
            Set<String> seenQueries
    ) {
        if (candidate == null) return null;
        String normalized = normalizeText(candidate);
        if (normalized.length() < 2 || normalized.length() > MAX_FOLLOWUP_CHARS) return null;

        String key = normalizeQuery(normalized);
        if (key.isBlank() || (seenQueries != null && seenQueries.contains(key))) return null;
        if (!hasTopicOverlap(original, normalized)
                && !hasTopicOverlap(current, normalized)) return null;
        return normalized;
    }

    public static String missingFallback(String original, String missing) {
        if (missing == null || missing.isBlank()) return null;
        String cleanedQuery = cleanCoreTopic(original);
        String cleanedMissing = normalizeText(missing);
        if (cleanedMissing.length() < 2) return null;
        return (cleanedQuery + " " + cleanedMissing).trim();
    }

    public static String narrowFallback(String original, int hop) {
        int index = Math.min(Math.max(hop - 1, 0), FALLBACK_HINTS.length - 1);
        return (cleanCoreTopic(original) + " " + FALLBACK_HINTS[index]).trim();
    }

    public static String normalizeQuery(String value) {
        return value == null
                ? ""
                : WHITESPACE.matcher(value).replaceAll(" ").trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasTopicOverlap(String base, String candidate) {
        Set<String> baseTerms = terms(base);
        if (baseTerms.isEmpty()) return true;
        String normalizedBase = normalizeTermText(base);
        for (String term : terms(candidate)) {
            if (baseTerms.contains(term) || normalizedBase.contains(term)) return true;
        }
        return false;
    }

    private static String normalizeTermText(String value) {
        return value == null
                ? ""
                : WHITESPACE.matcher(
                        TERM_PUNCTUATION.matcher(value.toLowerCase(Locale.ROOT)).replaceAll(""))
                .replaceAll("");
    }

    private static Set<String> terms(String value) {
        if (value == null) return Set.of();
        Matcher matcher = TERMS.matcher(value.toLowerCase(Locale.ROOT));
        Set<String> result = new HashSet<>();
        while (matcher.find()) {
            String term = matcher.group();
            if (!STOP_TERMS.contains(term)) result.add(term);
        }
        return result;
    }

    private static String cleanCoreTopic(String original) {
        return WHITESPACE.matcher(
                        CORE_TOPIC_NOISE.matcher(original == null ? "" : original)
                                .replaceAll(" "))
                .replaceAll(" ")
                .trim();
    }

    private static String normalizeText(String value) {
        return WHITESPACE.matcher(
                        CONTROL_CHARS.matcher(value).replaceAll(" "))
                .replaceAll(" ")
                .trim();
    }
}
