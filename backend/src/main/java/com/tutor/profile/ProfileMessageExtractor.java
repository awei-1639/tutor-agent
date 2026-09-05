package com.tutor.profile;

import com.tutor.contract.Purpose;
import com.tutor.llm.LlmMessage;
import com.tutor.llm.structured.ProfileExtractOutput;
import com.tutor.llm.structured.StructuredOutputResult;
import com.tutor.llm.structured.StructuredOutputService;
import com.tutor.llm.structured.StructuredTask;
import com.tutor.resume.PiiMasker;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Extracts and sanitizes profile facts from one user message. */
final class ProfileMessageExtractor {
    private static final Pattern GATE = Pattern.compile(
            "(我|本人|自己).*(会|学过|熟悉|掌握|懂|经验|年|应届|毕业|本科|硕士|大专|专科|转行|目标|想做|想当|想转|求职|城市|基地|每天|小时|偏好|喜欢|擅长)|零基础|应届生");

    private static final String EXTRACT_SYS = """
            你是画像信息抽取器。从用户消息中抽取求职学习相关的个人信息, 输出严格JSON:
            {"skills":[{"name":"技能名","explicit":true|false}],
             "scalars":{"target_position":{"value":"..","explicit":..}|null,"location":{..}|null,
                        "experience_years":{..}|null,"education":{..}|null,"daily_hours":{..}|null},
             "preferred_format":["视频","实战项目"...]}
            规则: explicit=用户明确陈述自己的事实("我会Java"); inferred=从语气推断("这个Python代码怎么改"暗示会Python)。
            只抽取消息中真实存在的信息, 没有就给空数组/null。禁止编造。技能名用通用中文名。
            """;

    private final StructuredOutputService structuredOutputService;

    ProfileMessageExtractor(StructuredOutputService structuredOutputService) {
        this.structuredOutputService = structuredOutputService;
    }

    static boolean eligible(String userMessage) {
        return GATE.matcher(userMessage).find();
    }

    ExtractedDelta extract(String userMessage, String traceId) {
        if (!eligible(userMessage)) return empty();
        String safeMessage = PiiMasker.mask(userMessage).masked();
        StructuredOutputResult<ProfileExtractOutput> structured = structuredOutputService.generate(
                StructuredTask.PROFILE_EXTRACT,
                Purpose.EXTRACT,
                List.of(LlmMessage.system(EXTRACT_SYS), LlmMessage.user("用户消息: " + safeMessage)),
                ProfileExtractOutput.class,
                this::validateProfileOutput,
                traceId
        );
        if (!structured.success()) return empty();
        return sanitizeDelta(toDelta(structured.value()));
    }

    private static ExtractedDelta empty() {
        return new ExtractedDelta(List.of(), Map.of(), List.of());
    }

    private void validateProfileOutput(ProfileExtractOutput output) {
        if (output.skills() == null || output.scalars() == null || output.preferredFormat() == null) {
            throw new IllegalArgumentException("profile extraction contains null collections");
        }
    }

    private ExtractedDelta toDelta(ProfileExtractOutput output) {
        List<ExtractedDelta.SkillDelta> skills = output.skills().stream()
                .map(skill -> new ExtractedDelta.SkillDelta(skill.name(), skill.explicit()))
                .toList();
        Map<String, ExtractedDelta.ScalarDelta> scalars = new HashMap<>();
        output.scalars().forEach((field, scalar) -> {
            if (scalar != null) {
                scalars.put(field, new ExtractedDelta.ScalarDelta(scalar.value(), scalar.explicit()));
            }
        });
        return new ExtractedDelta(skills, scalars, output.preferredFormat());
    }

    private ExtractedDelta sanitizeDelta(ExtractedDelta delta) {
        List<ExtractedDelta.SkillDelta> skills = delta.skills() == null ? List.of()
                : delta.skills().stream().map(s -> new ExtractedDelta.SkillDelta(
                        PiiMasker.mask(s.name()).masked(), s.explicit())).toList();
        Map<String, ExtractedDelta.ScalarDelta> scalars = new HashMap<>();
        if (delta.scalars() != null) {
            delta.scalars().forEach((field, value) -> scalars.put(field,
                    new ExtractedDelta.ScalarDelta(PiiMasker.mask(value.value()).masked(), value.explicit())));
        }
        List<String> formats = delta.preferredFormat() == null ? List.of()
                : delta.preferredFormat().stream().map(v -> PiiMasker.mask(v).masked()).toList();
        return new ExtractedDelta(skills, scalars, formats);
    }

}
