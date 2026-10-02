package com.tutor.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ToolRegistry {
    private final Map<String, ToolRegistration> tools = new ConcurrentHashMap<>();

    public ToolRegistry() { }

    @Autowired
    public ToolRegistry(List<ToolRegistration> registrations) {
        registrations.forEach(this::register);
    }

    public void register(ToolRegistration registration) {
        ToolRegistration previous = tools.putIfAbsent(registration.spec().name(), registration);
        if (previous != null) throw new IllegalStateException("工具重复注册: " + registration.spec().name());
    }

    /** 渲染某 agent 可用工具的人读目录，注入工具循环 system prompt；模型据此选择工具。 */
    public String catalogFor(String agent) {
        StringJoiner catalog = new StringJoiner("\n");
        tools.values().stream()
                .filter(r -> r.allowedAgents().contains(agent))
                .sorted(java.util.Comparator.comparing(r -> r.spec().name()))
                .forEach(r -> catalog.add("- " + r.spec().name() + "：" + r.spec().description()
                        + "（参数：" + paramNames(r.spec().inputSchema())
                        + "；副作用 " + r.spec().level() + "）"));
        return catalog.length() == 0 ? "（无）" : catalog.toString();
    }

    private static String paramNames(Class<?> schema) {
        var components = schema.getRecordComponents();
        if (components == null || components.length == 0) return "无";
        StringJoiner names = new StringJoiner("、");
        for (var c : components) names.add(c.getName());
        return names.toString();
    }

    public ToolRegistration require(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("工具名称不能为空");
        ToolRegistration registration = tools.get(name);
        if (registration == null) throw new ToolExecutionException("UNKNOWN_TOOL", "未知工具: " + name);
        return registration;
    }

    public Object convertInput(String name, JsonNode arguments, ObjectMapper mapper) {
        ToolRegistration registration = require(name);
        try {
            return mapper.treeToValue(arguments == null ? mapper.createObjectNode() : arguments,
                    registration.spec().inputSchema());
        } catch (Exception e) {
            throw new ToolExecutionException("INVALID_INPUT", "工具参数无法反序列化", e);
        }
    }
}
