package com.tutor.coaching.push;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MatchScorerTest {

    @Test
    void fullDirectMatchWithResume() {
        var r = MatchScorer.score(List.of("a", "b"), Set.of("a", "b"), Set.of(), 0.8);
        assertThat(r.coverage()).isEqualTo(1.0);
        assertThat(r.score()).isEqualTo(0.6 * 1.0 + 0.4 * 0.8);
        assertThat(r.matched()).containsExactly("a", "b");
        assertThat(MatchScorer.shouldPush(r, true, 0.65, 0.5)).isTrue();
    }

    @Test
    void speedupSkillCountsHalf() {
        // b缺口但前置已具备→0.5; coverage=(1+0.5)/2=0.75
        var r = MatchScorer.score(List.of("a", "b"), Set.of("a"), Set.of("b"), null);
        assertThat(r.coverage()).isEqualTo(0.75);
        assertThat(r.speedup()).containsExactly("b");
        assertThat(r.missing()).isEmpty();
    }

    @Test
    void coldStartUsesCoverageThresholdNotScore() {
        // 无简历: sim=0, score=0.6×coverage 永远到不了0.65总分线 → 按coverage≥0.5判定
        var r = MatchScorer.score(List.of("a", "b"), Set.of("a"), Set.of(), null);
        assertThat(r.coverage()).isEqualTo(0.5);
        assertThat(r.score()).isEqualTo(0.3);
        assertThat(MatchScorer.shouldPush(r, false, 0.65, 0.5)).isTrue();   // 冷启动线
        assertThat(MatchScorer.shouldPush(r, true, 0.65, 0.5)).isFalse();   // 有简历按总分线
    }

    @Test
    void breakdownIsExplainable() {
        var r = MatchScorer.score(List.of("a", "b", "c", "d"), Set.of("a"), Set.of("b"), 0.5);
        assertThat(r.matched()).containsExactly("a");
        assertThat(r.speedup()).containsExactly("b");
        assertThat(r.missing()).containsExactly("c", "d");
        assertThat(r.coverage()).isEqualTo((1 + 0.5) / 4.0);
    }
}
