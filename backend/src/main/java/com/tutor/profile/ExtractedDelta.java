package com.tutor.profile;

import java.util.List;
import java.util.Map;

/** LLM 画像抽取的增量结构 (LLM 只标注 explicit/inferred, 置信度数值由代码决定) */
public record ExtractedDelta(
        List<SkillDelta> skills,
        Map<String, ScalarDelta> scalars,   // target_position / location / experience_years / education / daily_hours
        List<String> preferredFormat
) {
    public record SkillDelta(String name, boolean explicit) {}
    public record ScalarDelta(String value, boolean explicit) {}

    public ScalarDelta scalar(String field) {
        return scalars == null ? null : scalars.get(field);
    }

    public boolean isEmpty() {
        return (skills == null || skills.isEmpty())
                && (scalars == null || scalars.isEmpty())
                && (preferredFormat == null || preferredFormat.isEmpty());
    }
}
