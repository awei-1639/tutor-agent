package com.tutor.platform.llm;

/** Provider-neutral metadata emitted once when a streaming generation completes. */
public record LlmStreamResult(String model, long inputTokens, long outputTokens, boolean truncated) {
}
