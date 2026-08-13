package com.tutor.interview;

/** Explicit interview state policy; adaptive signals can be added without changing persistence code. */
final class InterviewPolicy {
    enum Action { ASK_FOLLOW_UP, COMPLETE, ASK_MAIN }

    record Decision(Action action, int nextMainQuestionCount) {}

    Decision decide(String questionKind, int score, int currentMainQuestionCount, int mainQuestionLimit) {
        if ("MAIN".equals(questionKind) && score < 7) {
            return new Decision(Action.ASK_FOLLOW_UP, currentMainQuestionCount);
        }
        if (currentMainQuestionCount >= mainQuestionLimit) {
            return new Decision(Action.COMPLETE, currentMainQuestionCount);
        }
        return new Decision(Action.ASK_MAIN, currentMainQuestionCount + 1);
    }
}
