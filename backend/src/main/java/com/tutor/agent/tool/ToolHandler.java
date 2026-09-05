package com.tutor.agent.tool;

@FunctionalInterface
public interface ToolHandler {
    Object execute(Object input, ToolExecutionContext context) throws Exception;
}
