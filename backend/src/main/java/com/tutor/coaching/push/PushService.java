package com.tutor.coaching.push;

import com.tutor.identity.auth.AuthContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.identity.profile.ProfileService;
import com.tutor.identity.profile.SkillAlignService;
import com.tutor.platform.scheduling.ScheduledTaskLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 每日推送闭环 (V3 6.x): Mock注水释放 → 匹配打分 → push_tasks + notifications。
 * 冷启动: 画像空→只推引导消息不推岗位; 无简历→coverage门槛判定。
 */
@Service
public class PushService {
    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    private static final String CRON_LOCK = "push-scheduled-run";

    private final PushJobStore jobs;
    private final ProfileService profileService;
    private final SkillAlignService alignService;
    private final ScheduledTaskLock taskLock;
    private final NotificationStore notifications;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${push.match-threshold:0.65}") double matchThreshold;
    @Value("${push.cold-coverage-threshold:0.5}") double coldCoverageThreshold;
    @Value("${push.release-batch:5}") int releaseBatch;
    @Value("${push.max-per-run:5}") int maxPerRun;

    @Autowired
    public PushService(PushJobStore jobs, ProfileService profileService, SkillAlignService alignService,
                       ScheduledTaskLock taskLock, NotificationStore notifications) {
        this.jobs = jobs;
        this.profileService = profileService;
        this.alignService = alignService;
        this.taskLock = taskLock;
        this.notifications = notifications;
    }

    /** 单实例/测试构造器：无锁存储时始终作为 leader 执行。 */
    public PushService(JdbcTemplate jdbc, ProfileService profileService, SkillAlignService alignService) {
        this(new PushJobStore(jdbc), profileService, alignService, alwaysLeader(jdbc), new NotificationStore(jdbc));
    }

    private static ScheduledTaskLock alwaysLeader(JdbcTemplate jdbc) {
        return new ScheduledTaskLock(jdbc) {
            @Override
            public boolean tryAcquire(String taskName, long leaseSeconds) {
                return true;
            }
        };
    }

    @Scheduled(cron = "${push.cron:0 0 8,20 * * *}")
    public void scheduledRun() {
        // 多实例部署时，全量推送每个触发窗口只应执行一次，否则会给用户发重复通知。
        // 锁租约取一次运行的宽松上界；未抢到锁的实例直接跳过。
        taskLock.runIfLeader(CRON_LOCK, 1800, this::runScheduledBatch);
    }

    private void runScheduledBatch() {
        try {
            int released = releaseAvailableJobs();
            List<Long> userIds = jobs.userIds();
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
        return jobs.releaseAvailableJobs(releaseBatch);
    }

    /** 返回当前用户本次运行摘要 (also served by /internal/push-run) */
    @SuppressWarnings("unchecked")
    public Map<String, Object> runOnce() {
        // /internal 调用会注入演示用户；定时任务走 scheduledRun()，覆盖全部用户。
        return runForTool(AuthContext.requireUserId());
    }

    /** 工具执行器调用入口，显式传递用户，避免 ThreadLocal 跨虚拟线程丢失。 */
    public Map<String, Object> runForTool(long userId) {
        return runForUser(userId, releaseAvailableJobs());
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
        List<PushJobStore.Candidate> candidates = jobs.availableCandidates(uid);
        if (candidates.isEmpty()) return Map.of("released", released, "pushed", 0, "reason", "no_new_jobs");

        Set<String> profileIdSet = new HashSet<>(profileIds);
        List<String> allMissing = candidates.stream().flatMap(c -> c.requires().stream())
                .filter(r -> !profileIdSet.contains(r)).distinct().toList();
        Set<String> speedupables = alignService.speedupables(profileIds, allMissing);

        Optional<String> resumeEmbedding = jobs.latestResumeEmbedding(uid);
        boolean hasResume = resumeEmbedding.isPresent();

        List<Map<String, Object>> pushed = new ArrayList<>();
        var scored = new ArrayList<Map.Entry<PushJobStore.Candidate, MatchScorer.MatchResult>>();
        for (PushJobStore.Candidate c : candidates) {
            Double sim = null;
            if (hasResume) {
                sim = jobs.similarity(resumeEmbedding.orElseThrow(), c.id());
            }
            MatchScorer.MatchResult r = MatchScorer.score(c.requires(), profileIdSet, speedupables, sim);
            if (MatchScorer.shouldPush(r, hasResume, matchThreshold, coldCoverageThreshold)) {
                scored.add(Map.entry(c, r));
            }
        }
        scored.sort((a, b) -> Double.compare(b.getValue().score(), a.getValue().score()));
        for (var e : scored.stream().limit(maxPerRun).toList()) {
            PushJobStore.Candidate c = e.getKey();
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
                if (!jobs.claimPush(uid, c.id())) continue; // 与定时/手动并发时，已由另一轮推送成功领取。
                notifications.add(uid, "job_push", payload);
                pushed.add(Map.of("title", c.title(), "score", r.score()));
            } catch (Exception ex) {
                jobs.recordFailure(uid, c.id(), ex.getMessage());
                log.error("推送失败 job={}", c.id(), ex);
            }
        }
        log.info("推送完成 user={} released={} candidates={} pushed={}", uid, released, candidates.size(), pushed.size());
        return Map.of("released", released, "candidates", candidates.size(),
                "aligned_skills", profileIds.size(), "has_resume", hasResume, "pushed", pushed);
    }

    /** 引导消息去重: 存在未读引导则不重复发 */
    private void guideOnce(long uid, String text) {
        if (notifications.hasUnreadGuide(uid)) return;
        try {
            notifications.add(uid, "guide", mapper.writeValueAsString(Map.of("text", text)));
        } catch (Exception ignored) {}
    }
}
