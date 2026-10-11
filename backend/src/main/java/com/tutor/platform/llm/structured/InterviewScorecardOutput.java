package com.tutor.platform.llm.structured;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record InterviewScorecardOutput(
        int score,
        List<String> strengths,
        @JsonProperty("missing_points") List<String> missingPoints,
        double confidence,
        @JsonProperty("evidence_quotes") List<String> evidenceQuotes,
        /** 四维分项评分; 模型漏填时为 null, 报告侧按缺失处理而不是判失败。 */
        Map<String, Integer> dimensions,
        /** 边界意识观察: 候选人是否诚实标注不知道/不确定, 或是否编造内容。 */
        @JsonProperty("boundary_note") String boundaryNote
) {
}
