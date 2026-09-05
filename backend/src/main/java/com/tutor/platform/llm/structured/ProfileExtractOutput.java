package com.tutor.platform.llm.structured;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record ProfileExtractOutput(
        List<Skill> skills,
        Map<String, Scalar> scalars,
        @JsonProperty("preferred_format") List<String> preferredFormat
) {
    public record Skill(String name, boolean explicit) {
    }

    public record Scalar(String value, boolean explicit) {
    }
}
