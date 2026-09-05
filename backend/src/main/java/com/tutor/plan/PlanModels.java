package com.tutor.plan;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Stable data contracts owned by the plan module rather than its application service. */
public final class PlanModels {
    private PlanModels() {
    }

    public record Plan(long id, long userId, String goal, LocalDate weekStart, LocalDate weekEnd, String status) {
    }

    public record PlanTask(long id, long planId, LocalDate day, String content, String kind,
                           int minutes, String evidenceHint) {
    }

    public record Checkin(long id, long taskId, String status, String feedback) {
    }

    public record PlanGenerationJob(long id, String status, Long planId, String error,
                                    Instant createdAt, Instant finishedAt) {
    }

    record PlanTaskDraft(LocalDate day, String content, String kind,
                         List<String> relatedSkills, int minutes) {
    }
}
