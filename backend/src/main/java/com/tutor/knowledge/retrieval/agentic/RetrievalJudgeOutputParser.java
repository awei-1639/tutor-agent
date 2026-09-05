package com.tutor.knowledge.retrieval.agentic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Parses untrusted judge output without coupling the retrieval loop to JSON details. */
final class RetrievalJudgeOutputParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RetrievalJudgeOutputParser() {
    }

    static Decision parse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            return new Decision(
                    root.path("sufficient").asBoolean(false),
                    readTextOrArray(root.path("followup_query")),
                    readTextOrArray(root.path("missing")));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readTextOrArray(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            String value = node.asText(null);
            return value == null || value.isBlank() ? null : value;
        }
        if (!node.isArray()) return null;

        StringBuilder result = new StringBuilder();
        for (JsonNode item : node) {
            String value = readTextOrArray(item);
            if (value == null || value.isBlank()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(value.trim());
        }
        return result.length() == 0 ? null : result.toString();
    }

    record Decision(boolean sufficient, String followupQuery, String missing) {
    }
}
