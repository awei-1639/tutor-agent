package com.tutor.push;

import com.tutor.auth.AuthContext;

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
            int released = releaseAvailableJobs();
            List<Long> userIds = jdbc.queryForList("SELECT id FROM users ORDER BY id", Long.class);
            int processed = 0;
            for (Long userId : userIds) {
                try {
                    runForUser(userId, released);
                    processed++;
                } catch (Exception e) {
                    // 单个用户的画像/岗位数据异常不应中断其余用户的推送。
                    log.error("定时推送失败 user={}", userId, e);
                }
            }
            log.info("定时推送完成 users={} released={}", processed, released);
        } catch (Exception e) {
            log.error("定时推送任务初始化失败", e);
        }
    }

    private int releaseAvailableJobs() {
        return jdbc.update("""
                UPDATE jobs SET released = TRUE, fetched_at = now()
                WHERE id IN (SELECT id FROM jobs WHERE NOT released ORDER BY id LIMIT ?)
                """, releaseBatch);
    }

    /** 返回当前用户本次运行摘要 (also served by /internal/push-run) */
    @SuppressWarnings("unchecked")
    public Map<String, Object> runOnce() {
        // /internal 调用会注入演示用户；定时任务走 scheduledRun()，覆盖全部用户。
        long uid = AuthContext.requireUserId();
        return runForUser(uid, releaseAvailableJobs());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runForUser(long uid, int released) {
        // 画像与冷启动检查
        Map<String, Object> profile = profileService.snapshot(uid);
        List<Map<String, Object>> skills = profile.get("skills") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
        if (skills.isEmpty()) {
            guideOnce(uid, "完善你的技能画像或上传简历后，我就能开始为你推送匹配的岗位了");
            return Map.of("released", released, "pushed", 0, "reason", "empty_profile_guide");
        }

        // 技能对齐
        List<String> names = skills.stream().map(s -> String.valueOf(s.get("name"))).toList();
        Map<String, String> aligned = alignService.align(names);
        List<String> profileIds = aligned.values().stream().filter(x -> x != null).distinct().toList();

        // 候选岗位
        record Candidate(long id, String nodeId, String title, String company, String city,
                         String salary, List<String> requires) {}
        List<Candidate> candidates = jdbc.query("""
                SELECT id, node_id, title, company, city, salary, requires_raw FROM jobs
                WHERE released AND id NOT IN (SELECT job_id FROM push_tasks WHERE user_id = ? AND job_id IS NOT NULL)
                """, (rs, i) -> new Candidate(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6),
                List.of((String[]) rs.getArray(7).getArray())), uid);
        if (candidates.isEmpty()) return Map.of("released", released, "pushed", 0, "reason", "no_new_jobs");

        Set<String> profileIdSet = new HashSet<>(profileIds);
        List<String> allMissing = candidates.stream().flatMap(c -> c.requires().stream())
                .filter(r -> !profileIdSet.contains(r)).distinct().toList();
        Set<String> speedupables = alignService.speedupables(profileIds, allMissing);

        List<String> resumeVec = jdbc.query(
                "SELECT embedding::text FROM resumes WHERE user_id=? ORDER BY id DESC LIMIT 1",
                (rs, i) -> rs.getString(1), uid);
        boolean hasResume = !resumeVec.isEmpty();

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
                int inserted = jdbc.update("""
                        INSERT INTO push_tasks (user_id, job_id, status) VALUES (?,?,'sent')
                        ON CONFLICT (user_id, job_id) WHERE job_id IS NOT NULL DO NOTHING
                        """, uid, c.id());
                if (inserted == 0) continue; // 与定时/手动并发时，已由另一轮推送成功领取。
                jdbc.update("INSERT INTO notifications (user_id, type, payload) VALUES (?,'job_push',?::jsonb)", uid, payload);
                pushed.add(Map.of("title", c.title(), "score", r.score()));
            } catch (Exception ex) {
                jdbc.update("INSERT INTO push_tasks (user_id, job_id, status, retry_count, error) VALUES (?,?,'failed',0,?)",
                        uid, c.id(), ex.getMessage());
                log.error("推送失败 job={}", c.id(), ex);
            }
        }
        log.info("推送完成 user={} released={} candidates={} pushed={}", uid, released, candidates.size(), pushed.size());
        return Map.of("released", released, "candidates", candidates.size(),
                "aligned_skills", profileIds.size(), "has_resume", hasResume, "pushed", pushed);
    }

    /** 引导消息去重: 存在未读引导则不重复发 */
    private void guideOnce(long uid, String text) {
        Integer unreadGuides = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id=? AND type='guide' AND NOT read",
                Integer.class, uid);
        if (unreadGuides != null && unreadGuides > 0) return;
        try {
            jdbc.update("INSERT INTO notifications (user_id, type, payload) VALUES (?,'guide',?::jsonb)",
                    uid, mapper.writeValueAsString(Map.of("text", text)));
        } catch (Exception ignored) {}
    }
}
