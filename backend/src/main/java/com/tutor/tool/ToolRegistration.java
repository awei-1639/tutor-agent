package com.tutor.tool;

import com.tutor.contract.ToolSpec;

import java.util.Set;

public record ToolRegistration(
        ToolSpec spec,
        Set<String> allowedAgents,
        ToolHandler handler
) {
    public ToolRegistration {
        if (spec == null || spec.name() == null || spec.name().isBlank()) {
            throw new IllegalArgumentException("工具契约不能为空");
        }
        if (spec.inputSchema() == null || spec.timeout() == null || spec.timeout().isZero() || spec.timeout().isNegative()) {
            throw new IllegalArgumentException("工具契约必须声明合法 schema 和 timeout");
        }
        if (allowedAgents == null || allowedAgents.isEmpty()) throw new IllegalArgumentException("工具必须声明允许的 agent");
        if (allowedAgents.stream().anyMatch(agent -> agent == null || agent.isBlank())) throw new IllegalArgumentException("agent 权限不能为空");
        if (handler == null) throw new IllegalArgumentException("工具处理器不能为空");
        allowedAgents = Set.copyOf(allowedAgents);
    }
}
