package com.tutor.llm.structured;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record RouterOutput(
        String scope,
        String intent,
        List<String> intents,
        @JsonProperty("alternative_intent") String alternativeIntent,
        @JsonProperty("alternative_confidence") double alternativeConfidence,
        @JsonProperty("ambiguity_flags") List<String> ambiguityFlags,
        @JsonProperty("retrieval_facets") List<String> retrievalFacets,
        @JsonProperty("retrieval_hint") String retrievalHint,
        double confidence,
        @JsonProperty("reason_codes") List<String> reasonCodes
) {
}
