package com.tutor.push;

import com.tutor.profile.ProfileService;
import com.tutor.profile.SkillAlignService;
import com.tutor.plan.PlanService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将用户画像与岗位硬要求直接对照，供用户理解“下一步补什么”。不触发 LLM 调用。 */
@Service
public class CareerGapService {
    private final JdbcTemplate jdbc;
    private final ProfileService profiles;
    private final SkillAlignService alignments;
    private final PlanService plans;

    public CareerGapService(JdbcTemplate jdbc, ProfileService profiles, SkillAlignService alignments, PlanService plans) {
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.alignments = alignments;
        this.plans = plans;
    }

    public List<PlanService.PlanTask> addGapTasks(long userId, long jobId, List<String> skillIds) {
        Job job = jdbc.query("SELECT id, title, company, city, requires_raw FROM jobs WHERE id=? AND released", this::mapJob, jobId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("岗位不存在或不可用"));
        List<String> verified = skillIds == null ? List.of() : skillIds.stream()
                .filter(job.requires::contains).distinct().limit(3).toList();
        if (verified.isEmpty()) throw new IllegalArgumentException("请选择该岗位的待补齐技能");
        return plans.createEvidenceTasks(userId, "补齐「" + job.title + "」所需能力", verified);
    }

    @SuppressWarnings("unchecked")
    public List<GapCard> topGaps(long userId) {
        Map<String, Object> profile = profiles.snapshot(userId);
        List<String> skillNames = profile.get("skills") instanceof List<?> values
                ? values.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .map(value -> String.valueOf(value.get("name"))).toList() : List.of();
        List<String> alignedSkills = alignments.align(skillNames).values().stream()
                .filter(java.util.Objects::nonNull).distinct().toList();
        List<Job> jobs = jobsFor(targetPosition(profile));
        List<String> missing = jobs.stream().flatMap(job -> job.requires.stream())
                .filter(skill -> !alignedSkills.contains(skill)).distinct().toList();
        Set<String> speedupable = alignments.speedupables(alignedSkills, missing);
        Set<String> known = new HashSet<>(alignedSkills);
        return jobs.stream().map(job -> {
            MatchScorer.MatchResult score = MatchScorer.score(job.requires, known, speedupable, null);
            return new GapCard(job.id, job.title, job.company, job.city, score.coverage(),
                    score.matched(), score.speedup(), score.missing());
        }).toList();
    }

    private List<Job> jobsFor(String target) {
        String sql = "SELECT id, title, company, city, requires_raw FROM jobs WHERE released" +
                (target.isBlank() ? "" : " AND title ILIKE ?") + " ORDER BY id LIMIT 3";
        List<Job> matching = target.isBlank() ? jdbc.query(sql, this::mapJob)
                : jdbc.query(sql, this::mapJob, "%" + target + "%");
        return matching.isEmpty() && !target.isBlank()
                ? jdbc.query("SELECT id, title, company, city, requires_raw FROM jobs WHERE released ORDER BY id LIMIT 3", this::mapJob)
                : matching;
    }

    private Job mapJob(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        java.sql.Array array = rs.getArray(5);
        String[] requires = array == null ? new String[0] : (String[]) array.getArray();
        return new Job(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), List.of(requires));
    }

    @SuppressWarnings("unchecked")
    private String targetPosition(Map<String, Object> profile) {
        Object raw = profile.get("target_position");
        if (raw instanceof Map<?, ?> field && field.get("value") != null) return String.valueOf(field.get("value")).trim();
        return "";
    }

    private record Job(long id, String title, String company, String city, List<String> requires) {}
    public record GapCard(long jobId, String title, String company, String city, double coverage,
                          List<String> matched, List<String> speedup, List<String> missing) {}
}
