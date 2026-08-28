package com.tutor.llm.structured;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RetrievalJudgeOutput(
        boolean sufficient,
        @JsonProperty("followup_query") String followupQuery,
        String missing
) {
}
