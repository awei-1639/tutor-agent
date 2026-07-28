package com.tutor.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.Purpose;
import com.tutor.llm.LlmGateway;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * 学习计划闭环 (Phase 3 V4 3.2): LLM 生成周计划 → 落 plans/plan_tasks 表 → checkins 打卡 → 重规划触发
 * 重规划条件: 周完成率 < 60% 或用户反馈过难/过易 (V4 3.2 退出标准)
 */
@Service
public class PlanService {
    private static final Logger log = LoggerFactory.getLogger(PlanService.class);
    private static final double REPLAN_THRESHOLD = 0.6;
    private static final int PLAN_HORIZON_DAYS = 7;

    private static final String SYS = """
            你是周学习计划生成器。基于用户目标 + 当前技能水平 + 打卡历史, 生成未来 7 天每日任务。
            输出 JSON {"goal_summary":"...","days":[{"day":"周一","content":"...","kind":"learn|practice|review","related_skills":["技能名"],"estimated_minutes":60}]}
            - 任务要具体可执行 ("复习 Python 装饰器 1h" 而非 "学习Python")
            - 难度循序渐进: 新概念少, 复习多
            - 关联具体技能名 (与图谱一致)
            """;

    private final JdbcTemplate jdbc;
    private final LlmGateway gateway;
    private final ObjectMapper mapper = new ObjectMapper();

    public PlanService(JdbcTemplate jdbc, LlmGateway gateway) {
        this.jdbc = jdbc;
        this.gateway = gateway;
    }

    public record Plan(long id, long userId, String goal, LocalDate weekStart, LocalDate weekEnd, String status) {}
    public record PlanTask(long id, long planId, LocalDate day, String content, String kind, int minutes) {}
    public record Checkin(long id, long taskId, String status, String feedback) {}

    /** 生成新周计划: 调 LLM + 解析 + 入库 */
    public Plan generateWeeklyPlan(long userId, String goal, String currentSkills, String checkinHistory, String traceId) {
        try {
            String json = gateway.chatJson(Purpose.PLAN, List.of(
                    SystemMessage.from(SYS),
                    UserMessage.from("目标: " + goal + "\n当前技能: " + currentSkills + "\n近期打卡: " + checkinHistory)), traceId);
            var node = mapper.readTree(json);
            String goalSummary = node.path("goal_summary").asText(goal);
            LocalDate monday = LocalDate.now();
            while (monday.getDayOfWeek().getValue() != 1) monday = monday.minusDays(1);

            // 写 plans
            long planId = jdbc.queryForObject(
                    "INSERT INTO plans (user_id, goal, week_start, week_end, status) " +
                            "VALUES (?,?,?::date,?::date,?) RETURNING id",
                    Long.class, userId, goalSummary,
                    java.sql.Date.valueOf(monday),
                    java.sql.Date.valueOf(monday.plusDays(6)),
                    "active");

            // 写 plan_tasks
            int taskCount = 0;
            for (var dayNode : node.path("days")) {
                LocalDate day = monday.plusDays(taskCount % 7);
                jdbc.update(
                        "INSERT INTO plan_tasks (plan_id, user_id, day, content, kind, related_node_ids, estimated_minutes) " +
                                "VALUES (?,?,?,?,?,?::text[],?)",
                        planId, userId, java.sql.Date.valueOf(day),
                        dayNode.path("content").asText(""),
                        dayNode.path("kind").asText("learn"),
                        toTextArray(dayNode.path("related_skills")),
                        dayNode.path("estimated_minutes").asInt(60));
                taskCount++;
            }
            log.info("plan 生成 user={} plan={} tasks={}", userId, planId, taskCount);
            return new Plan(planId, userId, goalSummary, monday, monday.plusDays(6), "active");
        } catch (Exception e) {
            log.error("plan 生成失败 user={}: {}", userId, e.getMessage());
            return null;
        }
    }

    /** 用户打卡 */
    public Checkin checkin(long taskId, long userId, String status, String feedback) {
        long id = jdbc.queryForObject(
                "INSERT INTO checkins (task_id, user_id, status, feedback) VALUES (?,?,?,?) RETURNING id",
                Long.class, taskId, userId, status, feedback);
        return new Checkin(id, taskId, status, feedback);
    }

    /**
     * 重规划触发检测: 统计本周完成率, 若 <60% 或本周有"过难"反馈 → 触发重规划
     * 简化: 返回 boolean 让调用方决定是否调 generateWeeklyPlan
     */
    public boolean shouldReplan(long userId) {
        Long done = jdbc.queryForObject(
                "SELECT count(*) FROM checkins c JOIN plan_tasks t ON c.task_id=t.id " +
                        "WHERE c.user_id=? AND c.status='done' AND t.day BETWEEN (current_date - 7) AND current_date",
                Long.class, userId);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM plan_tasks WHERE user_id=? AND day BETWEEN (current_date - 7) AND current_date",
                Long.class, userId);
        if (total == null || total == 0) return false;
        double rate = (double) done / total;
        // 简化: 只看完成率
        if (rate < REPLAN_THRESHOLD) {
            log.info("触发重规划 user={} rate={} done={}/{}", userId, rate, done, total);
            return true;
        }
        return false;
    }

    /** 拉本周活跃计划的任务, 推送服务用 */
    public List<PlanTask> todayTasks(long userId) {
        return jdbc.query(
                "SELECT id, plan_id, day, content, kind, estimated_minutes FROM plan_tasks " +
                        "WHERE user_id=? AND day = current_date ORDER BY id",
                (rs, i) -> new PlanTask(rs.getLong(1), rs.getLong(2),
                        rs.getDate(3).toLocalDate(), rs.getString(4), rs.getString(5), rs.getInt(6)),
                userId);
    }

    private String toTextArray(com.fasterxml.jackson.databind.JsonNode arr) {
        if (arr == null || !arr.isArray()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var item : arr) {
            if (!first) sb.append(',');
            sb.append('"').append(item.asText().replace("\"", "\\\"")).append('"');
            first = false;
        }
        return sb.append('}').toString();
    }
}