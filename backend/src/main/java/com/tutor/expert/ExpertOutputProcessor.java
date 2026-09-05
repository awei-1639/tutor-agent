package com.tutor.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.ExpertOutput;
import com.tutor.contract.Purpose;
import com.tutor.llm.LlmMessage;
import com.tutor.llm.structured.ExpertPayload;
import com.tutor.llm.structured.StructuredOutputResult;
import com.tutor.llm.structured.StructuredOutputService;
import com.tutor.llm.structured.StructuredTask;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Validates and serializes one structured expert result. */
final class ExpertOutputProcessor {
    private static final int MAX_EVIDENCE_ITEMS = 10;
    private static final int MAX_EXPERT_JSON_CHARS = 12000;
    private static final int MAX_EXPERT_ITEMS = 20;
    private static final int MAX_ITEM_CHARS = 2000;

    private final StructuredOutputService structuredOutputService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExpertCitationValidator citationValidator = new ExpertCitationValidator();
    private final ExpertOutputValidator outputValidator = new ExpertOutputValidator();

    ExpertOutputProcessor(StructuredOutputService structuredOutputService) {
        this.structuredOutputService = structuredOutputService;
    }

    ExpertOutput process(String name, String systemPrompt, String briefing, String traceId, Duration timeout,
                                Set<String> availableCitationIds) {
        StructuredOutputResult<ExpertPayload> structured = structuredOutputService.generate(
                StructuredTask.EXPERT,
                Purpose.EXPERT,
                List.of(LlmMessage.system(systemPrompt), LlmMessage.user(briefing)),
                ExpertPayload.class,
                output -> validateExpertPayload(name, output, availableCitationIds),
                timeout,
                traceId
        );
        if (!structured.success()) {
            throw new IllegalStateException("专家结构化输出无效");
        }
        try {
            String content = mapper.writeValueAsString(structured.value());
            if (content.length() > MAX_EXPERT_JSON_CHARS) {
                throw new IllegalStateException("专家输出超过大小限制");
            }
            List<String> citations = structured.value().citations() == null
                    ? List.of() : List.copyOf(structured.value().citations());
            return new ExpertOutput(name, content, structured.value().confidence(), citations);
        } catch (Exception error) {
            throw new IllegalStateException("专家输出序列化失败", error);
        }
    }

    private void validateExpertPayload(
            String expert,
            ExpertPayload output,
            Set<String> availableCitationIds
    ) {
        JsonNode items = switch (expert) {
            case "resume" -> mapper.valueToTree(output.advice());
            case "interview" -> mapper.valueToTree(output.questions());
            case "planner" -> mapper.valueToTree(output.weeks());
            default -> throw new IllegalArgumentException("未知专家: " + expert);
        };
        if (items == null || !items.isArray() || items.size() > MAX_EXPERT_ITEMS) {
            throw new IllegalStateException("专家输出缺少合法内容数组");
        }
        validateItems(expert, items);
        citationValidator.validate(
                mapper.valueToTree(output.citations()),
                availableCitationIds,
                MAX_EVIDENCE_ITEMS);
    }

    private void validateItems(String expert, JsonNode items) {
        outputValidator.validateItems(expert, items, MAX_EXPERT_ITEMS, MAX_ITEM_CHARS);
    }

}
