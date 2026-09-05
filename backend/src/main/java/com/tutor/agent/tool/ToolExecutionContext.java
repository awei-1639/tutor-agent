package com.tutor.agent.tool;

public record ToolExecutionContext(
        String traceId,
        String agent,
        long userId,
        String idempotencyKey,
        boolean confirmed
) {
    public ToolExecutionContext {
        if (traceId == null || traceId.isBlank()) throw new IllegalArgumentException("traceId 不能为空");
        if (agent == null || agent.isBlank()) throw new IllegalArgumentException("agent 不能为空");
        if (userId <= 0) throw new IllegalArgumentException("userId 必须为正数");
    }
}
