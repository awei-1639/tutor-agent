package com.tutor.coaching.push;

import com.tutor.coaching.plan.PlanService;
import com.tutor.conversation.memory.application.LongTermMemoryService;
import com.tutor.platform.scheduling.ScheduledTaskLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 主动教练：每日聚合学习信号（未决事项/计划断档/完成率），在有值得跟进的内容时触达用户。
 * 教练只在有具体信号时说话，无信号保持沉默；每用户每日至多一条。
 */
@Service
public class ProactiveCoachService {
    private static final Logger log = LoggerFactory.getLogger(ProactiveCoachService.class);
    private static final int STREAK_BREAK_DAYS = 3;

    private final ScheduledTaskLock taskLock;
    private final NotificationStore notifications;
    private final PlanService plans;
    private final ObjectMapper mapper;
    private LongTermMemoryService memory;

    public ProactiveCoachService(ScheduledTaskLock taskLock, NotificationStore notifications,
                                 PlanService plans, ObjectMapper mapper) {
        this.taskLock = taskLock;
        this.notifications = notifications;
        this.plans = plans;
        this.mapper = mapper;
    }

    /** 记忆桥接为可选依赖：未注入时仅跳过未决事项信号。 */
    @Autowired(required = false)
    void setLongTermMemory(LongTermMemoryService memory) {
        this.memory = memory;
    }

    @Scheduled(cron = "${coach.cron:0 30 9 * * *}")
    public void scheduledRun() {
        // 多实例部署时每个触发窗口只应执行一次；未抢到锁的实例直接跳过。
        taskLock.runIfLeader("proactive-coach", 3600, this::runForAllUsers);
    }

    void runForAllUsers() {
        int sent = 0;
        for (Long userId : notifications.allUserIds()) {
            try {
                if (runForUser(userId)) sent++;
            } catch (Exception e) {
                // 单个用户的信号异常不应中断其余用户的触达。
                log.error("主动教练通知失败 user={}", userId, e);
            }
        }
        log.info("主动教练完成 sent={}", sent);
    }

    /** 单用户聚合；返回是否发出通知（也便于测试与后续 /internal 触发）。 */
    public boolean runForUser(long userId) {
        if (notifications.coachSentToday(userId)) return false;
        List<String> signals = collectSignals(userId);
        if (signals.isEmpty()) return false;
        try {
            notifications.add(userId, "coach_daily", mapper.writeValueAsString(Map.of(
                    "title", "今天，接着往前走",
                    "signals", signals)));
        } catch (Exception e) {
            throw new IllegalStateException("教练通知序列化失败", e);
        }
        log.info("主动教练触达 user={} signals={}", userId, signals.size());
        return true;
    }

    private List<String> collectSignals(long userId) {
        List<String> signals = new ArrayList<>();
        if (memory != null) {
            List<String> openItems = memory.recentOpenItems(userId, 1);
            if (!openItems.isEmpty()) {
                signals.add("上次还有未完成的事：「" + openItems.getFirst() + "」，今天开始吗？");
            }
        }
        if (plans.shouldReplan(userId)) {
            signals.add("本周计划完成率偏低，建议重新规划一周安排");
        }
        Instant lastCheckin = plans.lastCheckinAt(userId);
        if (lastCheckin != null && plans.hasRecentTasks(userId)
                && lastCheckin.isBefore(Instant.now().minus(Duration.ofDays(STREAK_BREAK_DAYS)))) {
            signals.add("学习计划已断档 " + STREAK_BREAK_DAYS + " 天，回来补上进度吧");
        }
        return signals;
    }
}
