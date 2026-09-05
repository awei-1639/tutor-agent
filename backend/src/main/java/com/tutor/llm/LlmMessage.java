package com.tutor.llm;

import java.util.Objects;

/**
 * Provider-neutral chat message used at the LLM boundary.
 * Provider SDK message types must not leak through gateway interfaces.
 */
public record LlmMessage(Role role, String content) {
    public LlmMessage {
        role = Objects.requireNonNull(role, "role");
        content = content == null ? "" : content;
    }

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT
    }

    public static LlmMessage system(String content) {
        return new LlmMessage(Role.SYSTEM, content);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(Role.USER, content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(Role.ASSISTANT, content);
    }
}
