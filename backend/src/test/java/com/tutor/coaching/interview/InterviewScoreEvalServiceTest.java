package com.tutor.coaching.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewScoreEvalServiceTest {
    @Test
    void evaluatesTheVersionedContractRegressionDataset() {
        Map<String, Object> result = new InterviewScoreEvalService(new ObjectMapper()).run();

        assertThat(result).containsEntry("kind", "deterministic_contract_baseline")
                .containsEntry("datasetVersion", "interview-score-contract-v1");
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) result.get("metrics");
        assertThat(metrics).containsEntry("n", 8).containsEntry("releaseEligible", true);
        assertThat(((Number) metrics.get("mae")).doubleValue()).isLessThanOrEqualTo(1.5);
    }

    @Test
    void marksASeverelyWrongHighScoreAsReleaseBlocking() {
        Map<String, Object> row = InterviewScoreEvalQuality.evaluateCase("bad", "", List.of("锁"), List.of(), List.of(), 2);
        row.put("actualScore", 8);
        row.put("absoluteError", 6);
        row.put("actualGrade", "strong");
        row.put("gradeMatched", false);
        row.put("falseHigh", true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) InterviewScoreEvalQuality.aggregate(List.of(row)).get("rules");
        assertThat(rules).anyMatch(rule -> "false_high_rate".equals(rule.get("code")) && Boolean.FALSE.equals(rule.get("passed")));
    }
}
