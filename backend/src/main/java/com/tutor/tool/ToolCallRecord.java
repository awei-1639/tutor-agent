package com.tutor.tool;

public record ToolCallRecord(
        String traceId,
        String agent,
        String tool,
        String argsDigest,
        String status,
        String sideEffect,
        long durationMs,
        String idempotencyKey
) {}
