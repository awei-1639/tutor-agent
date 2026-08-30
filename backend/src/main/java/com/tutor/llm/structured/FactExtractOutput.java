package com.tutor.llm.structured;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** FACT_EXTRACT 结构化输出契约：从已脱敏的用户消息窗口抽取的原子事实候选。 */
public record FactExtractOutput(
        @JsonProperty("facts") List<ExtractedFact> facts
) {

    public record ExtractedFact(
            String text,
            String category,
            Double confidence
    ) {
    }
}
