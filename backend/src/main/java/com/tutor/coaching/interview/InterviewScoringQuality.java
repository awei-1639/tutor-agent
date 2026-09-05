package com.tutor.coaching.interview;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Deterministic guardrails shared by generated assessment contracts and regression tests. */
final class InterviewScoringQuality {
    private InterviewScoringQuality() {}

    static boolean validContract(List<String> required, List<String> bonus, List<String> criticalErrors) {
        if (required == null || required.isEmpty()) return false;
        Set<String> requiredSet = normalized(required);
        Set<String> all = new HashSet<>(requiredSet);
        if (requiredSet.size() != required.size()) return false;
        for (String point : bonus == null ? List.<String>of() : bonus) {
            if (point == null || point.isBlank() || !all.add(normalize(point))) return false;
        }
        for (String point : criticalErrors == null ? List.<String>of() : criticalErrors) {
            if (point == null || point.isBlank() || requiredSet.contains(normalize(point))) return false;
        }
        return true;
    }

    /** A deterministic proxy used for contract regression, never as a replacement for model judgement. */
    static int evidenceScore(String answer, List<String> required, List<String> bonus, List<String> criticalErrors) {
        String text = normalize(answer);
        if ((criticalErrors == null ? List.<String>of() : criticalErrors).stream()
                .anyMatch(point -> containsEvidence(text, point))) return 2;
        long covered = (required == null ? List.<String>of() : required).stream()
                .filter(point -> containsEvidence(text, point)).count();
        double coverage = required == null || required.isEmpty() ? 0 : (double) covered / required.size();
        int score = (int) Math.round(2 + 6 * coverage);
        if (bonus != null && bonus.stream().anyMatch(point -> containsEvidence(text, point))) score++;
        return Math.max(0, Math.min(10, score));
    }

    private static boolean containsEvidence(String answer, String point) {
        String expected = normalize(point);
        return !expected.isBlank() && answer.contains(expected);
    }

    private static Set<String> normalized(List<String> values) {
        Set<String> result = new HashSet<>();
        for (String value : values) if (value != null && !value.isBlank()) result.add(normalize(value));
        return result;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "").trim();
    }
}
