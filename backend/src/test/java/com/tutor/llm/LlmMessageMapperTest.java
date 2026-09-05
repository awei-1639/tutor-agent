package com.tutor.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmMessageMapperTest {
    @Test
    void mapsSupportedProviderMessagesToStableBoundaryMessages() {
        List<ChatMessage> providerMessages = List.of(
                SystemMessage.from("rules"),
                UserMessage.from("question"),
                AiMessage.from("answer"));

        assertThat(LlmMessageMapper.fromLangChain(providerMessages))
                .containsExactly(
                        LlmMessage.system("rules"),
                        LlmMessage.user("question"),
                        LlmMessage.assistant("answer"));
    }

    @Test
    void mapsStableBoundaryMessagesBackToProviderMessagesWithoutChangingRoleOrContent() {
        List<ChatMessage> providerMessages = LlmMessageMapper.toLangChain(List.of(
                LlmMessage.system("rules"),
                LlmMessage.user("question"),
                LlmMessage.assistant("answer")));

        assertThat(providerMessages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(providerMessages.get(1)).isInstanceOf(UserMessage.class);
        assertThat(providerMessages.get(2)).isInstanceOf(AiMessage.class);
        assertThat(((SystemMessage) providerMessages.get(0)).text()).isEqualTo("rules");
        assertThat(((UserMessage) providerMessages.get(1)).singleText()).isEqualTo("question");
        assertThat(((AiMessage) providerMessages.get(2)).text()).isEqualTo("answer");
    }
}
