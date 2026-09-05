package com.tutor.coaching.interview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Computes model-vs-human scoring metrics from recorded, replayable outputs. */
final class InterviewModelEvalQuality {
    private InterviewModelEvalQuality() {}

    static Map<String, Object> aggregate(List<InterviewScoreEvalService.ReplayCase> cases) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (InterviewScoreEvalService.ReplayCase item : cases) {
            int human = item.humanScore();
            int model = item.modelScore();
            String humanGrade = grade(human);
            String modelGrade = grade(model);
            boolean matched = humanGrade.equals(modelGrade);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.id());
            row.put("humanScore", human);
            row.put("modelScore", model);
            row.put("absoluteError", Math.abs(model - human));
            row.put("humanGrade", humanGrade);
            row.put("modelGrade", modelGrade);
            row.put("gradeMatched", matched);
            row.put("falseHigh", human <= 3 && model >= 7);
            row.put("confidence", item.modelConfidence());
            row.put("reviewerCount", item.reviewerCount());
            row.put("humanScoreSpread", item.humanScoreSpread());
            row.put("singleReviewer", item.reviewerCount() < 2);
            row.put("reviewerDisagreement", item.reviewerCount() >= 2 && item.humanScoreSpread() > 1);
            row.put("highConfidenceError", item.modelConfidence() >= 0.8 && Math.abs(model - human) >= 2);
            rows.add(row);
        }
        double mae = average(rows, "absoluteError");
        double gradeAgreement = ratio(rows, "gradeMatched");
        double falseHighRate = ratio(rows, "falseHigh");
        double doubleLabelCoverage = 1D - ratio(rows, "singleReviewer");
        double disagreementRate = ratio(rows, "reviewerDisagreement");
        double highConfidenceErrorRate = ratio(rows, "highConfidenceError");
        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(rule("mae", "平均绝对误差", mae, 1.2, mae <= 1.2, "max"));
        rules.add(rule("grade_agreement", "三级评分一致率", gradeAgreement, 0.80, gradeAgreement >= 0.80, "min"));
        rules.add(rule("false_high_rate", "明显错误高分误判率", falseHighRate, 0.05, falseHighRate <= 0.05, "max"));
        rules.add(rule("double_label_coverage", "双人标注覆盖率", doubleLabelCoverage, 1.0, doubleLabelCoverage >= 1.0, "min"));
        rules.add(rule("reviewer_disagreement_rate", "人工评审分歧率", disagreementRate, 0.20, disagreementRate <= 0.20, "max"));
        rules.add(rule("high_confidence_error_rate", "高置信度大误差率", highConfidenceErrorRate, 0.05,
                highConfidenceErrorRate <= 0.05, "max"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("n", rows.size());
        result.put("mae", mae);
        result.put("gradeAgreement", gradeAgreement);
        result.put("falseHighRate", falseHighRate);
        result.put("doubleLabelCoverage", doubleLabelCoverage);
        result.put("reviewerDisagreementRate", disagreementRate);
        result.put("highConfidenceErrorRate", highConfidenceErrorRate);
        result.put("releaseEligible", !rows.isEmpty() && rules.stream().allMatch(rule -> Boolean.TRUE.equals(rule.get("passed"))));
        result.put("rules", rules);
        result.put("cases", rows);
        return result;
    }

    private static double average(List<Map<String, Object>> rows, String key) {
        return rows.stream().mapToDouble(row -> ((Number) row.get(key)).doubleValue()).average().orElse(0);
    }

    private static double ratio(List<Map<String, Object>> rows, String key) {
        if (rows.isEmpty()) return 0;
        return (double) rows.stream().filter(row -> Boolean.TRUE.equals(row.get(key))).count() / rows.size();
    }

    private static Map<String, Object> rule(String code, String label, double actual, double threshold,
                                            boolean passed, String comparator) {
        return new LinkedHashMap<>(Map.of("code", code, "label", label, "actual", actual,
                "threshold", threshold, "comparator", comparator, "passed", passed));
    }

    private static String grade(int score) {
        if (score < 5) return "insufficient";
        if (score < 7) return "qualified";
        return "strong";
    }
}
