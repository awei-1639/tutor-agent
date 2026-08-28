package com.tutor.llm.structured;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record InterviewScorecardOutput(
        int score,
        List<String> strengths,
        @JsonProperty("missing_points") List<String> missingPoints,
        double confidence,
        @JsonProperty("evidence_quotes") List<String> evidenceQuotes
) {
}
