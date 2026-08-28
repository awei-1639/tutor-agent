package com.tutor.llm.structured;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record PlanOutput(
        @JsonProperty("goal_summary") String goalSummary,
        List<Day> days
) {
    public record Day(
            String day,
            String content,
            String kind,
            @JsonProperty("related_skills") List<String> relatedSkills,
            @JsonProperty("estimated_minutes") int estimatedMinutes
    ) {
    }
}
