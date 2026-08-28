package com.tutor.llm.structured;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ExpertPayload(
        List<ResumeAdvice> advice,
        List<InterviewQuestion> questions,
        List<PlannerWeek> weeks,
        @JsonProperty("match_score") Double matchScore,
        double confidence,
        List<String> citations
) {
    public record ResumeAdvice(String point, String reason, Integer priority) {
    }

    public record InterviewQuestion(
            String q,
            String type,
            @JsonProperty("answer_points") String answerPoints
    ) {
    }

    public record PlannerWeek(
            Integer week,
            String goal,
            List<String> tasks,
            List<String> resources
    ) {
    }
}
