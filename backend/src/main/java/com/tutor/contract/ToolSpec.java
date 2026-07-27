package com.tutor.contract;

import java.time.Duration;

/** 工具契约 (实现设计 7.1): 注册表统一装配, 执行器按此校验/超时/重试 */
public record ToolSpec(
        String name,               // "kg_query"
        Class<?> inputSchema,      // 参数校验 (Bean Validation)
        Duration timeout,
        boolean idempotent,        // 决定可否自动重试
        SideEffect level
) {}
