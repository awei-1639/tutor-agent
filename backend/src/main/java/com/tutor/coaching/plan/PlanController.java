package com.tutor.coaching.plan;

import com.tutor.identity.auth.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    public record PlanRequest(@NotBlank @Size(max = 500) String goal,
                              @Size(max = 12000) String currentSkills,
                              @Size(max = 12000) String checkinHistory) {}

    @PostMapping
    public ResponseEntity<PlanModels.PlanGenerationJob> generate(
            @Valid @RequestBody PlanRequest req,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        PlanModels.PlanGenerationJob job = plans.enqueueWeeklyPlan(currentUserId(), req.goal(),
                req.currentSkills() == null ? "" : req.currentSkills(),
                req.checkinHistory() == null ? "" : req.checkinHistory(),
                traceId == null ? "user" : traceId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
    }

    @GetMapping("/jobs/{jobId}")
    public PlanModels.PlanGenerationJob generationJob(@PathVariable long jobId) {
        return plans.generationJob(currentUserId(), jobId);
    }

    public record CheckinRequest(long taskId, @NotBlank String status, String feedback) {}

    @PostMapping("/checkin")
    public PlanModels.Checkin checkin(@Valid @RequestBody CheckinRequest req) {
        return plans.checkin(req.taskId(), currentUserId(), req.status(), req.feedback());
    }

    @GetMapping("/today")
    public List<PlanModels.PlanTask> today() {
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
