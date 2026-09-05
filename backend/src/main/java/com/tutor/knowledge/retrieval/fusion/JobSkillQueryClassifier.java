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
            new Signal(Pattern.compile("技能|技术栈|笔试|胜任"), .65D),
            new Signal(Pattern.compile("需要|要求|掌握"), .60D),
            new Signal(Pattern.compile("skills|requirements|tech stack|qualifications"), .65D)
    );
    private static final List<Pattern> NEGATIVE = List.of(
            Pattern.compile("推荐.*(岗位|职位|公司)"),
            Pattern.compile("(岗位|职位)(推荐|列表)"));

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
        return new Decision(score >= THRESHOLD, Math.min(1D, score), List.copyOf(matched));
    }

    public record Decision(boolean seeking, double confidence, List<String> matchedSignals) { }
    private record Signal(Pattern pattern, double weight) { }
}
