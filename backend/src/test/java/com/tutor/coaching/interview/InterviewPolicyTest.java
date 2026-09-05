package com.tutor.coaching.interview;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewPolicyTest {
    private final InterviewPolicy policy = new InterviewPolicy();

    @Test
    void lowScoredMainQuestionGetsAFocusedFollowUp() {
        InterviewPolicy.Decision decision = policy.decide("MAIN", 6, 2, 3);

        assertThat(decision.action()).isEqualTo(InterviewPolicy.Action.ASK_FOLLOW_UP);
        assertThat(decision.nextMainQuestionCount()).isEqualTo(2);
    }

    @Test
    void reachingTheLimitCompletesMainAndFollowUpRounds() {
        assertThat(policy.decide("MAIN", 8, 3, 3).action()).isEqualTo(InterviewPolicy.Action.COMPLETE);
        assertThat(policy.decide("FOLLOW_UP", 8, 3, 3).action()).isEqualTo(InterviewPolicy.Action.COMPLETE);
    }

    @Test
    void otherwiseItAdvancesToTheNextMainQuestion() {
        InterviewPolicy.Decision decision = policy.decide("FOLLOW_UP", 8, 2, 3);

        assertThat(decision.action()).isEqualTo(InterviewPolicy.Action.ASK_MAIN);
        assertThat(decision.nextMainQuestionCount()).isEqualTo(3);
    }
}
