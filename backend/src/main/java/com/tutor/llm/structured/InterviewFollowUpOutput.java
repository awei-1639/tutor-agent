package com.tutor.llm.structured;

import com.fasterxml.jackson.annotation.JsonProperty;

public record InterviewFollowUpOutput(
        @JsonProperty("follow_up") String followUp
) {
}
