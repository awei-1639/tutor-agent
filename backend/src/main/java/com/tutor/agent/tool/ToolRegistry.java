package com.tutor.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.List;
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
