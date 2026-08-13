package com.tutor.interview;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewScoringQualityTest {
    @Test
    void acceptsDistinctAssessmentContractPoints() {
        assertThat(InterviewScoringQuality.validContract(
                List.of("互斥重建", "过期策略"), List.of("监控指标"), List.of("将击穿等同于雪崩"))).isTrue();
    }

    @Test
    void rejectsEmptyDuplicateAndContradictoryContractPoints() {
        assertThat(InterviewScoringQuality.validContract(List.of(), List.of(), List.of())).isFalse();
        assertThat(InterviewScoringQuality.validContract(List.of("缓存" , " 缓存 "), List.of(), List.of())).isFalse();
        assertThat(InterviewScoringQuality.validContract(List.of("缓存"), List.of("缓存"), List.of())).isFalse();
        assertThat(InterviewScoringQuality.validContract(List.of("缓存"), List.of(), List.of("缓存"))).isFalse();
    }

    @Test
    void producesStableProxyScoreForRegressionCases() {
        List<String> required = List.of("lock", "ttl");
        assertThat(InterviewScoringQuality.evidenceScore("lock ttl metrics", required, List.of("metrics"), List.of("snowstorm")))
                .isEqualTo(9);
        assertThat(InterviewScoringQuality.evidenceScore("snowstorm", required, List.of(), List.of("snowstorm")))
                .isEqualTo(2);
        assertThat(InterviewScoringQuality.evidenceScore("lock", required, List.of(), List.of())).isEqualTo(5);
    }
}
