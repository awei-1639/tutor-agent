package com.tutor.knowledge.retrieval.fusion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cheap, deterministic classifier for "某岗位要会什么技能" style queries
 * (Badcase 10). Mirrors {@link ResourceQueryClassifier}: regex signals with
 * weights, exposed for evaluation instead of hiding a boolean decision.
 */
public final class JobSkillQueryClassifier {
    private static final double THRESHOLD = 0.55D;
    private static final List<Signal> POSITIVE = List.of(
            new Signal(Pattern.compile(
                    "要会|得会|会啥|会什么|要掌握|掌握(哪些|什么)|需要(什么|哪些|啥)|必备|"
                            + "任职要求|岗位要求|能力要求|技能要求|招聘要求|有什么要求|要求是什么"), .75D),
            // 「要求什么技能」「得具备哪些能力」「得懂哪些东西」「要啥技能」——同样是问技能清单,
            // 只是动词不同; 单独成档, 避免被 0.55 阈值挡掉 (job 切片 7/60 曾因此漏判)。
            new Signal(Pattern.compile(
                    "要求(什么|哪些|啥)|具备(哪些|什么|什么能力|哪些能力)|懂哪些|得懂|要啥"), .72D),
            new Signal(Pattern.compile("技能|技术栈|笔试|胜任"), .65D),
            new Signal(Pattern.compile("需要|要求|掌握"), .60D),
            new Signal(Pattern.compile("skills|requirements|tech stack|qualifications"), .65D)
    );
    private static final List<Pattern> NEGATIVE = List.of(
            Pattern.compile("推荐.*(岗位|职位|公司)"),
            Pattern.compile("(岗位|职位)(推荐|列表)"));
    /**
     * 岗位语境（角色名词）。只有需求动词、没有角色名词的问法通常是「做某事需要什么」的
     * 通用建议，不是岗位需求——如「微调 BERT 做情感分析需要改哪些层」曾被判成技能寻求型，
     * 进而把一份 CV 岗位的 REQUIRES 技能塞进答案。缺岗位语境时按 NEGATIVE 同等降权。
     */
    private static final Pattern ROLE_CONTEXT = Pattern.compile(
            "工程师|开发|算法|实习|分析师|架构师|产品经理|运维|测试|岗|职位|岗位|专家|研究员|科学家|"
                    + "负责人|engineer|developer|scientist|intern|技术栈");
    private static final double NO_ROLE_PENALTY = .35D;

    private JobSkillQueryClassifier() { }

    public static Decision classify(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return new Decision(false, 0D, List.of());
        List<String> matched = new ArrayList<>();
        double score = 0D;
        for (Signal signal : POSITIVE) {
            if (signal.pattern.matcher(normalized).find()) {
                score = Math.max(score, signal.weight);
                matched.add(signal.pattern.pattern());
            }
        }
        if (NEGATIVE.stream().anyMatch(p -> p.matcher(normalized).find())) {
            score *= .35D;
            matched.add("negative_context");
        }
        if (!ROLE_CONTEXT.matcher(normalized).find()) {
            score *= NO_ROLE_PENALTY;
            matched.add("no_role_context");
        }
        return new Decision(score >= THRESHOLD, Math.min(1D, score), List.copyOf(matched));
    }

    public record Decision(boolean seeking, double confidence, List<String> matchedSignals) { }
    private record Signal(Pattern pattern, double weight) { }
}
