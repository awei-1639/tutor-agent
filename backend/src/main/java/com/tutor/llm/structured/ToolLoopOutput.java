package com.tutor.llm.structured;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public record ToolLoopOutput(
        String type,
        String tool,
        JsonNode arguments,
        String answer
) {
    public boolean isToolCall() {
        return "tool_call".equals(type);
    }

    public boolean isFinal() {
        return "final".equals(type);
    }
}
