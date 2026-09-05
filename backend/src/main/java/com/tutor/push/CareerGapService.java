package com.tutor.push;

import com.tutor.profile.ProfileService;
import com.tutor.profile.SkillAlignService;
import com.tutor.plan.PlanModels;
import com.tutor.plan.PlanService;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将用户画像与岗位硬要求直接对照，供用户理解“下一步补什么”。不触发 LLM 调用。 */
@Service
public class CareerGapService {
    private final CareerJobStore jobs;
    private final ProfileService profiles;
    private final SkillAlignService alignments;
    private final PlanService plans;

    public CareerGapService(CareerJobStore jobs, ProfileService profiles, SkillAlignService alignments, PlanService plans) {
        this.jobs = jobs;
        this.profiles = profiles;
        this.alignments = alignments;
        this.plans = plans;
    }

    public List<PlanModels.PlanTask> addGapTasks(long userId, long jobId, List<String> skillIds) {
        CareerJobStore.Job job = jobs.findReleasedById(jobId);
        List<String> verified = skillIds == null ? List.of() : skillIds.stream()
                .filter(job.requires()::contains).distinct().limit(3).toList();
        if (verified.isEmpty()) throw new IllegalArgumentException("请选择该岗位的待补齐技能");
        return plans.createEvidenceTasks(userId, "补齐「" + job.title() + "」所需能力", verified);
    }

    @SuppressWarnings("unchecked")
    public List<GapCard> topGaps(long userId) {
        Map<String, Object> profile = profiles.snapshot(userId);
        List<String> skillNames = profile.get("skills") instanceof List<?> values
                ? values.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .map(value -> String.valueOf(value.get("name"))).toList() : List.of();
        List<String> alignedSkills = alignments.align(skillNames).values().stream()
                .filter(java.util.Objects::nonNull).distinct().toList();
        List<CareerJobStore.Job> releasedJobs = jobs.findReleasedForTarget(targetPosition(profile));
        List<String> missing = releasedJobs.stream().flatMap(job -> job.requires().stream())
                .filter(skill -> !alignedSkills.contains(skill)).distinct().toList();
        Set<String> speedupable = alignments.speedupables(alignedSkills, missing);
        Set<String> known = new HashSet<>(alignedSkills);
        return releasedJobs.stream().map(job -> {
            MatchScorer.MatchResult score = MatchScorer.score(job.requires(), known, speedupable, null);
            return new GapCard(job.id(), job.title(), job.company(), job.city(), score.coverage(),
                    score.matched(), score.speedup(), score.missing());
        }).toList();
    }

    @SuppressWarnings("unchecked")
    private String targetPosition(Map<String, Object> profile) {
        Object raw = profile.get("target_position");
        if (raw instanceof Map<?, ?> field && field.get("value") != null) return String.valueOf(field.get("value")).trim();
        return "";
    }

    public record GapCard(long jobId, String title, String company, String city, double coverage,
                          List<String> matched, List<String> speedup, List<String> missing) {}
}
