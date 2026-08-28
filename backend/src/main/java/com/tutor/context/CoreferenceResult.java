package com.tutor.context;

import java.util.List;

public record CoreferenceResult(
        String originalQuery,
        String resolvedQuery,
        List<Reference> references,
        boolean needsClarification
) {
    public record Reference(
            String mention,
            String resolvedTo,
            double confidence
    ) {
    }

    public static CoreferenceResult unchanged(String query) {
        return new CoreferenceResult(query, query, List.of(), false);
    }

    public static CoreferenceResult clarification(String query, String mention) {
        return new CoreferenceResult(
                query,
                query,
                List.of(new Reference(mention, "", 0D)),
                true
        );
    }
}
