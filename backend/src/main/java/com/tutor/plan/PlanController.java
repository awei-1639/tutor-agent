package com.tutor.plan;

import com.tutor.auth.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 学习计划 HTTP API (Phase 3 V4 3.2)
 * 端点: POST /plans 生成周计划; POST /plans/checkin 打卡; GET /plans/today 今日任务
 */
@RestController
@RequestMapping("/plans")
public class PlanController {
    private final PlanService plans;

    public PlanController(PlanService plans) {
        this.plans = plans;
    }

    public record PlanRequest(@NotBlank String goal, String currentSkills, String checkinHistory) {}

    @PostMapping
    public Object generate(@Valid @RequestBody PlanRequest req, @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        PlanService.Plan p = plans.generateWeeklyPlan(currentUserId(), req.goal(), req.currentSkills() == null ? "" : req.currentSkills(),
                req.checkinHistory() == null ? "" : req.checkinHistory(), traceId == null ? "user" : traceId);
        return p == null ? java.util.Map.of("error", "plan 生成失败") : p;
    }

    public record CheckinRequest(long taskId, @NotBlank String status, String feedback) {}

    @PostMapping("/checkin")
    public PlanService.Checkin checkin(@Valid @RequestBody CheckinRequest req) {
        return plans.checkin(req.taskId(), currentUserId(), req.status(), req.feedback());
    }

    @GetMapping("/today")
    public List<PlanService.PlanTask> today() {
        return plans.todayTasks(currentUserId());
    }

    @GetMapping("/should-replan")
    public Object shouldReplan() {
        return java.util.Map.of("should_replan", plans.shouldReplan(currentUserId()));
    }

    private long currentUserId() {
        Long userId = AuthContext.currentUserId();
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证");
        return userId;
    }
}
