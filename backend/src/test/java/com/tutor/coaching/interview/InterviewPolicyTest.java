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
    void reachingTheLimitOpensTheCandidateQuestionRound() {
        // 主线问完后不直接结束, 而是进入反问环节 —— 真实面试的最后一步。
        assertThat(policy.decide("MAIN", 8, 3, 3).action()).isEqualTo(InterviewPolicy.Action.ASK_CANDIDATE);
        assertThat(policy.decide("FOLLOW_UP", 8, 3, 3).action()).isEqualTo(InterviewPolicy.Action.ASK_CANDIDATE);
    }

    @Test
    void candidateRoundContinuesUntilTheSecondQuestionIsAnswered() {
        // 第一个反问被回答后还能再问一个
        assertThat(policy.decideCandidatePhase(true, 0).action()).isEqualTo(InterviewPolicy.Action.ASK_CANDIDATE);
        // 已经问过 1 个, 本次回答后即达到上限
        assertThat(policy.decideCandidatePhase(true, 1).action()).isEqualTo(InterviewPolicy.Action.COMPLETE);
    }

    @Test
    void declaringNoQuestionEndsTheCandidateRound() {
        assertThat(policy.decideCandidatePhase(false, 0).action()).isEqualTo(InterviewPolicy.Action.COMPLETE);
        assertThat(policy.decideCandidatePhase(false, 1).action()).isEqualTo(InterviewPolicy.Action.COMPLETE);
    }

    @Test
    void recognisesWhetherTheCandidateActuallyAskedSomething() {
        assertThat(InterviewPolicy.hasQuestion("团队现在最棘手的技术问题是什么？")).isTrue();
        assertThat(InterviewPolicy.hasQuestion("你们有没有新人导师制度")).isTrue();
        assertThat(InterviewPolicy.hasQuestion("新人前三个月一般做什么")).isTrue();
        assertThat(InterviewPolicy.hasQuestion("没有问题")).isFalse();
        assertThat(InterviewPolicy.hasQuestion("暂时没有，谢谢")).isFalse();
        assertThat(InterviewPolicy.hasQuestion("no")).isFalse();
        assertThat(InterviewPolicy.hasQuestion("   ")).isFalse();
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
