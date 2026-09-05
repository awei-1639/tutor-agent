package com.tutor.platform.llm.structured;

import java.util.List;

public record CitationGuardOutput(
        List<Claim> claims,
        String summary
) {
    public record Claim(String text, String sid, String verdict) {
    }
}
