package com.tutor.expert;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/** 校验各类专家输出项的字段、数量和取值范围。 */
final class ExpertOutputValidator {
    void validateItems(String expert, JsonNode items, int maxItems, int maxChars) {
        try {
            for (JsonNode item : items) {
                switch (expert) {
                    case "resume" -> validateResume(item, maxChars);
                    case "interview" -> validateInterview(item, maxChars);
                    case "planner" -> validatePlanner(item, maxItems, maxChars);
                    default -> throw new IllegalArgumentException("未知专家: " + expert);
                }
            }
        } catch (Exception error) {
            throw new IllegalStateException("专家 " + expert + " 输出项结构不合法", error);
        }
    }

    private void validateResume(JsonNode item, int maxChars) {
        text(item, "point", maxChars); text(item, "reason", maxChars);
        int priority = item.path("priority").asInt(0);
        if (priority < 1 || priority > 5) throw new IllegalArgumentException("priority 超出范围");
    }

    private void validateInterview(JsonNode item, int maxChars) {
        text(item, "q", maxChars); String type = text(item, "type", maxChars); text(item, "answer_points", maxChars);
        if (!Set.of("笔试", "面试").contains(type)) throw new IllegalArgumentException("type 不合法");
    }

    private void validatePlanner(JsonNode item, int maxItems, int maxChars) {
        int week = item.path("week").asInt(0);
        if (week < 1 || week > 8) throw new IllegalArgumentException("week 超出范围");
        text(item, "goal", maxChars); textArray(item, "tasks", maxItems, maxChars); textArray(item, "resources", maxItems, maxChars);
    }

    private String text(JsonNode item, String field, int maxChars) {
        String value = item.path(field).asText("");
        if (value.isBlank() || value.length() > maxChars) throw new IllegalArgumentException(field + " 不能为空或过长");
        return value;
    }

    private void textArray(JsonNode item, String field, int maxItems, int maxChars) {
        JsonNode values = item.get(field);
        if (values == null || !values.isArray() || values.size() > maxItems) throw new IllegalArgumentException(field + " 结构不合法");
        values.forEach(value -> { if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > maxChars) throw new IllegalArgumentException(field + " 不能为空或过长"); });
    }
}
