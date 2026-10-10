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

    @Test
    void followUpChainProbesOneLayerDeeperBeforeReturningToTheMainLine() {
        // 第一层追问仍没答清 -> 进入第二层边界探测
        InterviewPolicy.Decision probe = policy.decide("FOLLOW_UP", 5, 2, 3, 1);
        assertThat(probe.action()).isEqualTo(InterviewPolicy.Action.ASK_FOLLOW_UP);
        assertThat(probe.followUpDepth()).isEqualTo(2);

        // 第二层之后再不追问, 无论得分如何都回到主线
        InterviewPolicy.Decision back = policy.decide("FOLLOW_UP", 4, 2, 3, 2);
        assertThat(back.action()).isEqualTo(InterviewPolicy.Action.ASK_MAIN);
        assertThat(back.nextMainQuestionCount()).isEqualTo(3);
    }

    @Test
    void aGoodFollowUpAnswerEndsTheProbeImmediately() {
        InterviewPolicy.Decision decision = policy.decide("FOLLOW_UP", 8, 2, 3, 1);

        assertThat(decision.action()).isEqualTo(InterviewPolicy.Action.ASK_MAIN);
    }
}
