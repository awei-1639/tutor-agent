package com.tutor.interview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Metrics and release gate for the deterministic interview scoring-contract baseline. */
final class InterviewScoreEvalQuality {
    private InterviewScoreEvalQuality() {}

    static Map<String, Object> evaluateCase(String id, String answer, List<String> required, List<String> bonus,
                                            List<String> criticalErrors, int expectedScore) {
        int actualScore = InterviewScoringQuality.evidenceScore(answer, required, bonus, criticalErrors);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("expectedScore", expectedScore);
        result.put("actualScore", actualScore);
        result.put("absoluteError", Math.abs(actualScore - expectedScore));
        result.put("expectedGrade", grade(expectedScore));
        result.put("actualGrade", grade(actualScore));
        result.put("gradeMatched", grade(expectedScore).equals(grade(actualScore)));
        result.put("falseHigh", expectedScore <= 3 && actualScore >= 7);
        return result;
    }

    static Map<String, Object> aggregate(List<Map<String, Object>> rows) {
        double mae = rows.stream().mapToDouble(row -> ((Number) row.get("absoluteError")).doubleValue()).average().orElse(0);
        long gradeMatched = rows.stream().filter(row -> Boolean.TRUE.equals(row.get("gradeMatched"))).count();
        long falseHigh = rows.stream().filter(row -> Boolean.TRUE.equals(row.get("falseHigh"))).count();
        double agreement = rows.isEmpty() ? 0 : (double) gradeMatched / rows.size();
        double falseHighRate = rows.isEmpty() ? 0 : (double) falseHigh / rows.size();

        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(rule("mae", "平均绝对误差", mae, 1.5, mae <= 1.5, "max"));
        rules.add(rule("grade_agreement", "三级评分一致率", agreement, 0.80, agreement >= 0.80, "min"));
        rules.add(rule("false_high_rate", "明显错误高分误判率", falseHighRate, 0.05, falseHighRate <= 0.05, "max"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("n", rows.size());
        result.put("mae", mae);
        result.put("gradeAgreement", agreement);
        result.put("falseHighRate", falseHighRate);
        result.put("releaseEligible", rules.stream().allMatch(rule -> Boolean.TRUE.equals(rule.get("passed"))));
        result.put("rules", rules);
        return result;
    }

    private static Map<String, Object> rule(String code, String label, double actual, double threshold, boolean passed, String comparator) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("label", label);
        result.put("actual", actual);
        result.put("threshold", threshold);
        result.put("comparator", comparator);
        result.put("passed", passed);
        return result;
    }

    private static String grade(int score) {
        if (score < 5) return "insufficient";
        if (score < 7) return "qualified";
        return "strong";
    }
}
