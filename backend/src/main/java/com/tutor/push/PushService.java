package com.tutor.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.profile.ProfileService;
import com.tutor.profile.SkillAlignService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 每日推送闭环 (V3 6.x): Mock注水释放 → 匹配打分 → push_tasks + notifications。
 * 冷启动: 画像空→只推引导消息不推岗位; 无简历→coverage门槛判定。
 */
@Service
public class PushService {
    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    private static final long DEV_USER_ID = 1L;

    private final JdbcTemplate jdbc;
    private final ProfileService profileService;
    private final SkillAlignService alignService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${push.match-threshold:0.65}") double matchThreshold;
    @Value("${push.cold-coverage-threshold:0.5}") double coldCoverageThreshold;
    @Value("${push.release-batch:5}") int releaseBatch;
    @Value("${push.max-per-run:5}") int maxPerRun;

    public PushService(JdbcTemplate jdbc, ProfileService profileService, SkillAlignService alignService) {
        this.jdbc = jdbc;
        this.profileService = profileService;
        this.alignService = alignService;
    }

    @Scheduled(cron = "${push.cron:0 0 8,20 * * *}")
    public void scheduledRun() {
        try {
            runOnce();
        } catch (Exception e) {
            log.error("定时推送失败", e); // 定时任务失败不抛出, 下轮重试
        }
    }

    /** 返回本次运行摘要 (also served by /internal/push-run) */
    @SuppressWarnings("unchecked")
    public Map<String, Object> runOnce() {
        // 1) Mock注水释放: 模拟"每日新增岗位" (V3 6.x — 静态数据下扫描新增永远为空的问题)
        int released = jdbc.update("""
                UPDATE jobs SET released = TRUE, fetched_at = now()
                WHERE id IN (SELECT id FROM jobs WHERE NOT released ORDER BY id LIMIT ?)
                """, releaseBatch);

        // 2) 画像与冷启动检查
        Map<String, Object> profile = profileService.snapshot(DEV_USER_ID);
        List<Map<String, Object>> skills = profile.get("skills") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
        if (skills.isEmpty()) {
            guideOnce("完善你的技能画像或上传简历后，我就能开始为你推送匹配的岗位了");
            return Map.of("released", released, "pushed", 0, "reason", "empty_profile_guide");
        }

        // 3) 技能对齐 (name→node_id)
        List<String> names = skills.stream().map(s -> String.valueOf(s.get("name"))).toList();
        Map<String, String> aligned = alignService.align(names);
        List<String> profileIds = aligned.values().stream().filter(x -> x != null).distinct().toList();

        // 4) 候选岗位: 已释放且未推送过
        record Candidate(long id, String nodeId, String title, String company, String city,
                         String salary, List<String> requires) {}
        List<Candidate> candidates = jdbc.query("""
                SELECT id, node_id, title, company, city, salary, requires_raw FROM jobs
                WHERE released AND id NOT IN (SELECT job_id FROM push_tasks WHERE user_id = ? AND job_id IS NOT NULL)
                """, (rs, i) -> new Candidate(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6),
                List.of((String[]) rs.getArray(7).getArray())), DEV_USER_ID);
        if (candidates.isEmpty()) return Map.of("released", released, "pushed", 0, "reason", "no_new_jobs");

        // 5) 可速成集合 (全部缺口一次性查图)
        Set<String> profileIdSet = new HashSet<>(profileIds);
        List<String> allMissing = candidates.stream()
                .flatMap(c -> c.requires().stream())
                .filter(r -> !profileIdSet.contains(r))
                .distinct().toList();
        Set<String> speedupables = alignService.speedupables(profileIds, allMissing);

        // 6) 简历向量 (无简历→null, 冷启动规则生效)
        List<String> resumeVec = jdbc.query(
                "SELECT embedding::text FROM resumes WHERE user_id=? ORDER BY id DESC LIMIT 1",
                (rs, i) -> rs.getString(1), DEV_USER_ID);
        boolean hasResume = !resumeVec.isEmpty();

        // 7) 打分推送
        List<Map<String, Object>> pushed = new ArrayList<>();
        var scored = new ArrayList<Map.Entry<Candidate, MatchScorer.MatchResult>>();
        for (Candidate c : candidates) {
            Double sim = null;
            if (hasResume) {
                sim = jdbc.queryForObject("SELECT 1 - (embedding <=> ?::vector) FROM jobs WHERE id=?",
                        Double.class, resumeVec.get(0), c.id());
            }
            MatchScorer.MatchResult r = MatchScorer.score(c.requires(), profileIdSet, speedupables, sim);
            if (MatchScorer.shouldPush(r, hasResume, matchThreshold, coldCoverageThreshold)) {
                scored.add(Map.entry(c, r));
            }
        }
        scored.sort((a, b) -> Double.compare(b.getValue().score(), a.getValue().score()));
        for (var e : scored.stream().limit(maxPerRun).toList()) {
            Candidate c = e.getKey();
            MatchScorer.MatchResult r = e.getValue();
            try {
                String payload = mapper.writeValueAsString(Map.ofEntries(
                        Map.entry("job_id", c.id()), Map.entry("node_id", c.nodeId()),
                        Map.entry("title", c.title()), Map.entry("company", c.company()),
                        Map.entry("city", c.city()), Map.entry("salary", c.salary()),
                        Map.entry("score", Math.round(r.score() * 100) / 100.0),
                        Map.entry("coverage", Math.round(r.coverage() * 100) / 100.0),
                        Map.entry("matched", r.matched()), Map.entry("speedup", r.speedup()),
                        Map.entry("missing", r.missing())));
                jdbc.update("INSERT INTO push_tasks (user_id, job_id, status) VALUES (?,?,'sent')",
                        DEV_USER_ID, c.id());
                jdbc.update("INSERT INTO notifications (user_id, type, payload) VALUES (?,'job_push',?::jsonb)",
                        DEV_USER_ID, payload);
                pushed.add(Map.of("title", c.title(), "score", r.score()));
            } catch (Exception ex) {
                jdbc.update("INSERT INTO push_tasks (user_id, job_id, status, retry_count, error) VALUES (?,?,'failed',0,?)",
                        DEV_USER_ID, c.id(), ex.getMessage());
                log.error("推送失败 job={}", c.id(), ex);
            }
        }
        log.info("推送完成: released={} candidates={} pushed={}", released, candidates.size(), pushed.size());
        return Map.of("released", released, "candidates", candidates.size(),
                "aligned_skills", profileIds.size(), "has_resume", hasResume, "pushed", pushed);
    }

    /** 引导消息去重: 存在未读引导则不重复发 */
    private void guideOnce(String text) {
        Integer unreadGuides = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id=? AND type='guide' AND NOT read",
                Integer.class, DEV_USER_ID);
        if (unreadGuides != null && unreadGuides > 0) return;
        try {
            jdbc.update("INSERT INTO notifications (user_id, type, payload) VALUES (?,'guide',?::jsonb)",
                    DEV_USER_ID, mapper.writeValueAsString(Map.of("text", text)));
        } catch (Exception ignored) {}
    }
}
