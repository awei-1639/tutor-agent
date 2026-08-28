package com.tutor.llm.structured;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record InterviewQuestionOutput(
        String question,
        @JsonProperty("required_points") List<String> requiredPoints,
        @JsonProperty("bonus_points") List<String> bonusPoints,
        @JsonProperty("critical_errors") List<String> criticalErrors
) {
}
