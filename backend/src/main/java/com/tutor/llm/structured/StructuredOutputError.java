package com.tutor.llm.structured;

public record StructuredOutputError(
        String source,
        String path,
        String message
) {
}
