package com.tutor.llm.structured;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record EpisodeSummaryOutput(
        String summary,
        List<String> topics,
        @JsonProperty("open_items") List<String> openItems
) {
}
