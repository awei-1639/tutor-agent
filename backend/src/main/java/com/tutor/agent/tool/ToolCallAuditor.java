package com.tutor.agent.tool;

@FunctionalInterface
public interface ToolCallAuditor {
    void record(ToolCallRecord call);
}
