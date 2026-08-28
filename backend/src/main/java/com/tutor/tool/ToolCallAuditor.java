package com.tutor.tool;

@FunctionalInterface
public interface ToolCallAuditor {
    void record(ToolCallRecord call);
}
