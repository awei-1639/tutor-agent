package com.tutor.tool;

@FunctionalInterface
public interface ToolHandler {
    Object execute(Object input, ToolExecutionContext context) throws Exception;
}
