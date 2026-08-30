package com.tutor.memory;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 低成本确定性的字符 bigram 相似度，用于记忆的近重复/冲突判定。
 * 刻意不调用 embedding：候选消解必须可单测、可复现，且不引入额外外部依赖。
 */
public final class BigramSimilarity {

    private BigramSimilarity() {
    }

    /** 去标点/空白并小写，作为比较的规范化形态。 */
    public static String canonical(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "");
    }

    /** Jaccard 相似度（字符 bigram 集合）；短文本（<20 字符）直接比较 canonical 全等。 */
    public static double similarity(String left, String right) {
        String a = canonical(left);
        String b = canonical(right);
        if (a.equals(b)) return 1.0D;
        if (a.length() < 20 || b.length() < 20) return 0.0D;
        Set<String> bigramsA = bigrams(a);
        Set<String> bigramsB = bigrams(b);
        Set<String> intersection = new HashSet<>(bigramsA);
        intersection.retainAll(bigramsB);
        Set<String> union = new HashSet<>(bigramsA);
        union.addAll(bigramsB);
        return union.isEmpty() ? 0.0D : (double) intersection.size() / union.size();
    }

    /**
     * 包含度：text 的 bigram 有多少出现在 within 中（0~1）。
     * 用于"事实是否被当前问题提及"这类非对称匹配——问题通常远长于事实，
     * Jaccard 会被问题的额外词汇稀释，包含度不会。
     */
    public static double containment(String text, String within) {
        String a = canonical(text);
        String b = canonical(within);
        if (a.isEmpty() || b.isEmpty()) return 0.0D;
        Set<String> bigramsA = bigrams(a);
        if (bigramsA.isEmpty()) return 0.0D;
        Set<String> bigramsB = bigrams(b);
        Set<String> kept = new HashSet<>(bigramsA);
        kept.retainAll(bigramsB);
        return (double) kept.size() / bigramsA.size();
    }

    private static Set<String> bigrams(String text) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i + 1 < text.length(); i++) result.add(text.substring(i, i + 2));
        return result;
    }
}
