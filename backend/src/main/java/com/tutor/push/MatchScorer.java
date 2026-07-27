package com.tutor.push;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 岗位匹配打分 — 纯函数可单测 (V3 6.1): match = 0.6×skill_coverage + 0.4×semantic_sim。
 * 可解释优先: 每个分数可拆解为 命中/可速成/缺口 三组技能。
 * 冷启动 (V3 6.x): 无简历→semantic_sim项置0, 仅当coverage≥coldThreshold才推。
 */
public final class MatchScorer {
    public static final double W_COVERAGE = 0.6;
    public static final double W_SEMANTIC = 0.4;
    public static final double SPEEDUP_CREDIT = 0.5;

    private MatchScorer() {}

    public record MatchResult(
            double score,
            double coverage,
            double semanticSim,
            List<String> matched,      // 直接命中的技能id
            List<String> speedup,      // 可速成(前置已具备)的缺口技能id
            List<String> missing       // 纯缺口技能id
    ) {}

    public static MatchResult score(List<String> requires, Set<String> profileSkillIds,
                                    Set<String> speedupableIds, Double semanticSim) {
        List<String> matched = new ArrayList<>();
        List<String> speedup = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String r : requires) {
            if (profileSkillIds.contains(r)) matched.add(r);
            else if (speedupableIds.contains(r)) speedup.add(r);
            else missing.add(r);
        }
        double coverage = requires.isEmpty() ? 0
                : (matched.size() + SPEEDUP_CREDIT * speedup.size()) / requires.size();
        double sim = semanticSim == null ? 0 : semanticSim;
        double score = W_COVERAGE * coverage + W_SEMANTIC * sim;
        return new MatchResult(score, coverage, sim, matched, speedup, missing);
    }

    /** 推送判定: 有简历比总分, 无简历比覆盖率 (语义项缺失时0.65总分线数学上不可达) */
    public static boolean shouldPush(MatchResult r, boolean hasResume,
                                     double threshold, double coldCoverageThreshold) {
        return hasResume ? r.score() >= threshold : r.coverage() >= coldCoverageThreshold;
    }
}
