package com.tutor.platform.llm.structured;

import java.util.List;

public record StructuredOutputResult<T>(
        boolean success,
        T value,
        String rawOutput,
        boolean repaired,
        int attempts,
        List<StructuredOutputError> errors
) {
    public StructuredOutputResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static <T> StructuredOutputResult<T> failure(
            String rawOutput,
            int attempts,
            List<StructuredOutputError> errors
    ) {
        return new StructuredOutputResult<>(
                false, null, rawOutput, false, attempts, errors);
    }
}
