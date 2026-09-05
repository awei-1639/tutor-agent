package com.tutor.knowledge.retrieval.fusion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cheap, deterministic resource-intent classifier used before reranking and
 * resource-channel damping. It remains a fallback heuristic, but exposes a
 * confidence and matched signals so routing quality can be evaluated instead
 * of hiding a boolean regex decision.
 */
public final class ResourceQueryClassifier {
    private static final double THRESHOLD = 0.55D;
    private static final List<Signal> POSITIVE = List.of(
            new Signal(Pattern.compile("课程|教程|教材|视频|书籍|题库|资料|学习材料|参考资料|文献|链接|资源"), .75D),
            new Signal(Pattern.compile("找一份|找些|给我找|哪里学|去哪学|在哪学|推荐.*(学|看|读|学习)|适合.*(学习|入门|复习)"), .70D),
            new Signal(Pattern.compile("github|paper|论文|course|courseware|tutorial|material|resource|book|video"), .65D)
    );
    private static final List<Pattern> NEGATIVE = List.of(
            Pattern.compile("推荐.*(岗位|职位|简历|面试|公司)"),
            Pattern.compile("推荐.*(方案|做法|算法|架构)"));

    private ResourceQueryClassifier() { }

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
