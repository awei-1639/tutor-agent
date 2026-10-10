package com.tutor.coaching.interview;

/** Explicit interview state policy; adaptive signals can be added without changing persistence code. */
final class InterviewPolicy {
    /** 追问最多两层: 第一层验证缺失点, 第二层探测知识边界。 */
    static final int MAX_FOLLOW_UP_DEPTH = 2;
    /** 低于该分的回答视为"未说清", 触发追问。 */
    static final int FOLLOW_UP_SCORE_THRESHOLD = 7;

    enum Action { ASK_FOLLOW_UP, COMPLETE, ASK_MAIN }

    record Decision(Action action, int nextMainQuestionCount, int followUpDepth) {
        Decision(Action action, int nextMainQuestionCount) {
            this(action, nextMainQuestionCount, 1);
        }
    }

    Decision decide(String questionKind, int score, int currentMainQuestionCount, int mainQuestionLimit) {
        return decide(questionKind, score, currentMainQuestionCount, mainQuestionLimit, 1);
    }

    /**
     * followUpDepth 是当前问题所处的追问层（主线题为 0）。追问链最多两层：
     * 第二层是边界探测，答不上来本身就是有效信号，不再继续追。
     */
    Decision decide(String questionKind, int score, int currentMainQuestionCount, int mainQuestionLimit,
                    int followUpDepth) {
        boolean followUp = "FOLLOW_UP".equals(questionKind);
        if (followUp) {
            if (followUpDepth < MAX_FOLLOW_UP_DEPTH && score < FOLLOW_UP_SCORE_THRESHOLD) {
                return new Decision(Action.ASK_FOLLOW_UP, currentMainQuestionCount, followUpDepth + 1);
            }
        } else if (score < FOLLOW_UP_SCORE_THRESHOLD) {
            return new Decision(Action.ASK_FOLLOW_UP, currentMainQuestionCount, 1);
        }
        if (currentMainQuestionCount >= mainQuestionLimit) {
            return new Decision(Action.COMPLETE, currentMainQuestionCount, 0);
        }
        return new Decision(Action.ASK_MAIN, currentMainQuestionCount + 1, 0);
    }
}
