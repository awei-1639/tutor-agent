package com.tutor.coaching.plan;

import com.tutor.contract.Purpose;
import com.tutor.platform.llm.LlmBudgetGuard;
import com.tutor.platform.llm.structured.PlanOutput;
import com.tutor.platform.llm.structured.StructuredOutputResult;
import com.tutor.platform.llm.structured.StructuredOutputService;
import com.tutor.platform.llm.structured.StructuredTask;
import com.tutor.platform.llm.LlmMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.tutor.coaching.plan.PlanModels.Checkin;
import static com.tutor.coaching.plan.PlanModels.Plan;
import static com.tutor.coaching.plan.PlanModels.PlanGenerationJob;
import static com.tutor.coaching.plan.PlanModels.PlanTask;
import static com.tutor.coaching.plan.PlanModels.PlanTaskDraft;

/**
 * Learning-plan application service. It coordinates model generation and deterministic plan rules;
 * PostgreSQL details and durable worker lifecycle live in dedicated adapters.
 */
@Service
public class PlanService {
    private static final Logger log = LoggerFactory.getLogger(PlanService.class);
    private static final double REPLAN_THRESHOLD = 0.6;
    private static final int PLAN_HORIZON_DAYS = 7;
    private static final Set<String> PLAN_KINDS = Set.of("learn", "practice", "review");
    private static final String EVIDENCE_HINT = "提交可运行示例、练习答案或复盘笔记之一。";

    private static final String SYS = """
            你是周学习计划生成器。基于用户目标 + 当前技能水平 + 打卡历史, 生成未来 7 天每日任务。
            输出 JSON {"goal_summary":"...","days":[{"day":"周一","content":"...","kind":"learn|practice|review","related_skills":["技能名"],"estimated_minutes":60}]}
            - 任务要具体可执行 ("复习 Python 装饰器 1h" 而非 "学习Python")
            - 难度循序渐进: 新概念少, 复习多
            - 关联具体技能名 (与图谱一致)
            - 若请求附带"用户画像与历史证据", 必须据此安排: 优先覆盖面试验证的薄弱项,
              任务量不超过每日可学时长, 资源形式贴合偏好, 已掌握的高置信技能不要重复排练
            """;

    private final PlanStore store;
    private final StructuredOutputService structuredOutputService;
    private volatile LlmBudgetGuard budgetGuard;
    private volatile PlanContextService planContextService;

    public PlanService(PlanStore store, StructuredOutputService structuredOutputService) {
        this.store = store;
        this.structuredOutputService = structuredOutputService;
    }

    /** Optionally attributes plan-generation usage to a user-level budget. */
    @Autowired(required = false)
    void setBudgetGuard(LlmBudgetGuard budgetGuard) {
        this.budgetGuard = budgetGuard;
    }

    /**
     * Optionally enriches generation with profile, long-term facts and interview evidence.
     * Absent (tests, minimal contexts) the planner falls back to the caller-supplied strings.
     */
    @Autowired(required = false)
    void setPlanContextService(PlanContextService planContextService) {
        this.planContextService = planContextService;
    }

    /** Enqueue quickly so the HTTP request does not wait for the model. */
    public PlanGenerationJob enqueueWeeklyPlan(long userId, String goal, String currentSkills,
                                               String checkinHistory, String traceId) {
        if (budgetGuard != null && traceId != null) {
            try {
                budgetGuard.attributeTrace(traceId, userId);
            } catch (RuntimeException error) {
                log.warn("budget attribution failed trace={} type={}", traceId,
                        error.getClass().getSimpleName());
            }
        }
        Optional<Long> id = store.enqueueGeneration(userId, goal, currentSkills, checkinHistory, traceId);
        if (id.isEmpty()) {
            // 已有排队/运行中的生成任务: 复用而不是产生重复计划
            id = store.findActiveGenerationJobId(userId);
        }
        if (id.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "计划生成任务已在排队中");
        }
        return generationJob(userId, id.get());
    }

    public PlanGenerationJob generationJob(long userId, long jobId) {
        return store.findGenerationJob(userId, jobId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "计划生成任务不存在"));
    }

    /** Generate and persist a seven-day plan. Returns null on model or validation failure. */
    public Plan generateWeeklyPlan(long userId, String goal, String currentSkills,
                                   String checkinHistory, String traceId) {
        try {
            String learnerContext = learnerContextBlock(userId);
            StructuredOutputResult<PlanOutput> structured = structuredOutputService.generate(
                    StructuredTask.PLAN,
                    Purpose.PLAN,
                    List.of(
                            LlmMessage.system(SYS),
                            LlmMessage.user("目标: " + goal + "\n当前技能: " + currentSkills
                                    + "\n近期打卡: " + checkinHistory + learnerContext)),
                    PlanOutput.class,
                    output -> {
                        if (output.goalSummary() == null || output.goalSummary().isBlank()
                                || output.days() == null || output.days().size() != PLAN_HORIZON_DAYS) {
                            throw new IllegalArgumentException("计划必须包含目标摘要和 7 天任务");
                        }
                    },
                    traceId
            );
            if (!structured.success()) return null;

            PlanOutput output = structured.value();
            String goalSummary = clip(output.goalSummary(), 300);
            LocalDate monday = startOfWeek(LocalDate.now());
            List<PlanTaskDraft> tasks = new ArrayList<>(PLAN_HORIZON_DAYS);
            int taskIndex = 0;
            for (PlanOutput.Day day : output.days()) {
                tasks.add(new PlanTaskDraft(
                        monday.plusDays(taskIndex % PLAN_HORIZON_DAYS),
                        clip(day.content(), 500),
                        safeKind(day.kind()),
                        day.relatedSkills(),
                        boundedMinutes(day.estimatedMinutes())));
                taskIndex++;
            }

            Plan plan = store.saveGeneratedPlan(userId, goalSummary, monday, tasks);
            log.info("plan 生成 user={} plan={} tasks={}", userId, plan.id(), tasks.size());
            return plan;
        } catch (Exception error) {
            log.error("plan 生成失败 user={}: {}", userId, error.getMessage());
            return null;
        }
    }

    public Checkin checkin(long taskId, long userId, String status, String feedback) {
        if (!store.taskExists(taskId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "task 不存在: id=" + taskId);
        }
        return store.addCheckin(taskId, userId, status, feedback);
    }

    public boolean shouldReplan(long userId) {
        PlanStore.PlanProgress progress = store.progress(userId);
        if (progress.total() == 0) return false;
        double rate = (double) progress.done() / progress.total();
        if (rate < REPLAN_THRESHOLD) {
            log.info("触发重规划 user={} rate={} done={}/{}", userId, rate,
                    progress.done(), progress.total());
            return true;
        }
        return false;
    }

    public List<PlanTask> todayTasks(long userId) {
        return store.todayTasks(userId);
    }

    /** Convert verified skill gaps into at most three executable tasks for the current week. */
    public List<PlanTask> createEvidenceTasks(long userId, String goal, List<String> skillIds) {
        LocalDate today = LocalDate.now();
        LocalDate monday = startOfWeek(today);
        LocalDate sunday = monday.plusDays(6);
        long planId = store.activePlanIdOrCreate(userId, goal, monday, sunday);

        List<PlanTask> created = new ArrayList<>();
        int offset = 0;
        for (String skillId : new LinkedHashSet<>(skillIds).stream().limit(3).toList()) {
            if (store.hasEvidenceTask(userId, skillId, today, sunday)) continue;
            LocalDate day = today.plusDays(Math.min(offset++,
                    Math.max(0, sunday.toEpochDay() - today.toEpochDay())));
            String display = skillId.replace("skill:", "").replace('-', ' ');
            created.add(store.addEvidenceTask(
                    planId,
                    userId,
                    day,
                    skillId,
                    "完成「" + display + "」的一个针对性练习",
                    EVIDENCE_HINT));
        }
        return created;
    }

    /** Best-effort learner context; never blocks or fails plan generation. */
    private String learnerContextBlock(long userId) {
        PlanContextService context = this.planContextService;
        if (context == null) return "";
        try {
            return context.load(userId).toPromptBlock();
        } catch (Exception error) {
            log.warn("计划上下文加载失败, 退化为仅使用请求入参 user={}: {}", userId, error.getMessage());
            return "";
        }
    }

    private LocalDate startOfWeek(LocalDate day) {
        return day.minusDays(day.getDayOfWeek().getValue() - 1L);
    }

    private String clip(String value, int maxChars) {
        if (value == null) return "";
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "…";
    }

    private String safeKind(String value) {
        String kind = clip(value, 40).toLowerCase(Locale.ROOT);
        return PLAN_KINDS.contains(kind) ? kind : "learn";
    }

    private int boundedMinutes(int value) {
        return Math.max(5, Math.min(480, value));
    }
}
