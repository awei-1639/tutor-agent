package com.tutor.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;

/** Adapter between the provider-neutral contract and LangChain4j's provider model. */
public final class LlmMessageMapper {
    private LlmMessageMapper() {
    }

    public static List<LlmMessage> fromLangChain(List<ChatMessage> messages) {
        if (messages == null) return List.of();
        return messages.stream().filter(java.util.Objects::nonNull).map(LlmMessageMapper::fromLangChain).toList();
    }

    public static LlmMessage fromLangChain(ChatMessage message) {
        if (message instanceof SystemMessage system) return LlmMessage.system(system.text());
        if (message instanceof UserMessage user && user.hasSingleText()) return LlmMessage.user(user.singleText());
        if (message instanceof AiMessage ai && ai.text() != null) return LlmMessage.assistant(ai.text());
        return LlmMessage.user(message.toString());
    }

    public static List<ChatMessage> toLangChain(List<LlmMessage> messages) {
        if (messages == null) return List.of();
        return messages.stream().filter(java.util.Objects::nonNull).map(LlmMessageMapper::toLangChain).toList();
    }

    public static ChatMessage toLangChain(LlmMessage message) {
        return switch (message.role()) {
            case SYSTEM -> SystemMessage.from(message.content());
            case USER -> UserMessage.from(message.content());
            case ASSISTANT -> AiMessage.from(message.content());
        };
    }
}
