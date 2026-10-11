package com.tutor.coaching.interview;

import java.util.List;

/** Explicit interview state policy; adaptive signals can be added without changing persistence code. */
final class InterviewPolicy {
    /** 追问最多两层: 第一层验证缺失点, 第二层探测知识边界。 */
    static final int MAX_FOLLOW_UP_DEPTH = 2;
    /** 低于该分的回答视为"未说清", 触发追问。 */
    static final int FOLLOW_UP_SCORE_THRESHOLD = 7;
    /** 反问环节最多记录几个候选人的提问。 */
    static final int MAX_CANDIDATE_QUESTIONS = 2;
    /** 候选人明确表示没有要问时的常见表述。 */
    private static final List<String> DECLINE_CUES = List.of(
            "没有", "没问题", "无问题", "不需要", "暂时没有", "暂无", "就这样", "可以了", "不用了",
            "no", "none", "nothing", "nope", "skip");
    /** 带这些词的短句即使没写问号也按提问处理, 避免"有没有/是不是"被误判成放弃反问。 */
    private static final List<String> QUESTION_CUES = List.of(
            "有没有", "是不是", "会不会", "能不能", "可不可以", "吗", "呢", "怎么", "怎样", "如何", "为什么",
            "什么", "哪些", "多少", "是否", "哪个", "谁", "when", "what", "how", "why", "which");

    enum Action { ASK_FOLLOW_UP, COMPLETE, ASK_MAIN, ASK_CANDIDATE }

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
        // 主线题问完后进入反问环节: 真实面试的最后一步, 换候选人向面试官提问。
        if (currentMainQuestionCount >= mainQuestionLimit) {
            return new Decision(Action.ASK_CANDIDATE, currentMainQuestionCount, 0);
        }
        return new Decision(Action.ASK_MAIN, currentMainQuestionCount + 1, 0);
    }

    /**
     * 反问环节的推进: 已经问了 candidateQuestionsAsked 个问题, 本次内容是候选人刚提交的话。
     * 没有问题或问题数达标即结束面试; 否则继续邀请下一个反问。
     */
    Decision decideCandidatePhase(boolean hasQuestion, int candidateQuestionsAsked) {
        if (!hasQuestion || candidateQuestionsAsked + 1 >= MAX_CANDIDATE_QUESTIONS) {
            return new Decision(Action.COMPLETE, 0, 0);
        }
        return new Decision(Action.ASK_CANDIDATE, 0, 0);
    }

    /**
     * 候选人是否真的提了问题。带问号的算提问; 短句里出现"没有/暂无/no"等表述视为放弃反问。
     * 误判为"没有"只会提前结束反问环节并提交复盘, 代价可控。
     */
    static boolean hasQuestion(String answer) {
        if (answer == null) return false;
        String raw = answer.trim();
        if (raw.isBlank()) return false;
        if (raw.contains("?") || raw.contains("？")) return true;
        String text = raw.replaceAll("[\\s\\p{Punct}]", "").toLowerCase();
        if (text.isBlank()) return false;
        if (QUESTION_CUES.stream().anyMatch(text::contains)) return true;
        return text.length() >= 2 && DECLINE_CUES.stream().noneMatch(text::contains);
    }
}
