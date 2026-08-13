package com.tutor.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterviewModelEvalQualityTest {
    private final InterviewScoreEvalService service = new InterviewScoreEvalService(new ObjectMapper());

    @Test
    void computesModelHumanAgreementAndBlocksFalseHighScores() {
        Map<String, Object> result = service.replay(new InterviewScoreEvalService.ReplayRequest("gold-v1", List.of(
                new InterviewScoreEvalService.ReplayCase("a", 9, 9, 0.9),
                new InterviewScoreEvalService.ReplayCase("b", 6, 6, 0.7),
                new InterviewScoreEvalService.ReplayCase("c", 2, 2, 0.9),
                new InterviewScoreEvalService.ReplayCase("d", 2, 8, 0.95))));

        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) result.get("metrics");
        assertThat(metrics).containsEntry("n", 4).containsEntry("releaseEligible", false);
        assertThat(((Number) metrics.get("falseHighRate")).doubleValue()).isEqualTo(0.25D);
    }

    @Test
    void rejectsInvalidRecordedScores() {
        assertThatThrownBy(() -> service.replay(new InterviewScoreEvalService.ReplayRequest("gold-v1", List.of(
                new InterviewScoreEvalService.ReplayCase("bad", 11, 5, 0.5)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesReviewerDisagreementInsteadOfHidingItInTheAverage() {
        Map<String, Object> result = service.replay(new InterviewScoreEvalService.ReplayRequest("gold-v1", List.of(
                new InterviewScoreEvalService.ReplayCase("agreed", 8, 8, 0.8, 2, 1),
                new InterviewScoreEvalService.ReplayCase("split", 6, 6, 0.8, 2, 2))));

        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) result.get("metrics");
        assertThat(((Number) metrics.get("reviewerDisagreementRate")).doubleValue()).isEqualTo(0.5D);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) metrics.get("rules");
        assertThat(rules).anySatisfy(rule -> assertThat(rule)
                .containsEntry("code", "reviewer_disagreement_rate")
                .containsEntry("passed", false));
    }

    @Test
    void doesNotTreatSingleReviewerSamplesAsReleaseEvidence() {
        Map<String, Object> result = service.replay(new InterviewScoreEvalService.ReplayRequest("gold-v1", List.of(
                new InterviewScoreEvalService.ReplayCase("single", 8, 8, 0.8))));

        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) result.get("metrics");
        assertThat(metrics).containsEntry("doubleLabelCoverage", 0D).containsEntry("releaseEligible", false);
    }
}
