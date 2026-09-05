package com.tutor.platform.llm.structured;

public record StructuredOutputError(
        String source,
        String path,
        String message
) {
}
