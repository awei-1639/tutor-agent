package com.tutor.coaching.push;

import com.tutor.identity.auth.AuthContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

@RestController
public class CareerGapController {
    private final CareerGapService gaps;

    public CareerGapController(CareerGapService gaps) {
        this.gaps = gaps;
    }

    @GetMapping("/career/gaps")
    public List<CareerGapService.GapCard> topGaps() {
        Long userId = AuthContext.currentUserId();
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "未认证");
        return gaps.topGaps(userId);
    }

    public record AddTasksRequest(long jobId, @NotEmpty List<String> skillIds) {}

    @org.springframework.web.bind.annotation.PostMapping("/career/gaps/tasks")
    public List<com.tutor.coaching.plan.PlanModels.PlanTask> addTasks(@Valid @org.springframework.web.bind.annotation.RequestBody AddTasksRequest request) {
        Long userId = AuthContext.currentUserId();
        if (userId == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "未认证");
        return gaps.addGapTasks(userId, request.jobId(), request.skillIds());
    }
}
