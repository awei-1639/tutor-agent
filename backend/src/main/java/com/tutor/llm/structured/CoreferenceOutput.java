package com.tutor.llm.structured;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CoreferenceOutput(
        @JsonProperty("resolved_query") String resolvedQuery,
        @JsonProperty("resolved_to") String resolvedTo,
        double confidence,
        @JsonProperty("needs_clarification") boolean needsClarification
) {
}
