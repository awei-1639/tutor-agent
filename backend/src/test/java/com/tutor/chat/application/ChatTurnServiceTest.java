package com.tutor.chat.application;

import com.tutor.memory.local.ConversationStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatTurnServiceTest {
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private ChatTurnService service;

    @AfterEach
    void close() {
        if (service != null) service.shutdown();
        metrics.close();
    }

    @Test
    void lostLeaseCannotPersistAssistantMessage() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ConversationStore conversations = mock(ConversationStore.class);
        ObjectProvider<ChatService> provider = mock(ObjectProvider.class);
        service = new ChatTurnService(jdbc, conversations, provider, metrics);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        ChatTurnService.Claim claim = new ChatTurnService.Claim(
                UUID.randomUUID().toString(), 7L, 9L, "question", "trace", 1, UUID.randomUUID());

        OptionalLong result = service.completeWithMessage(claim, "answer", "chat", null,
                2, "not_applicable", "[]");

        assertThat(result).isEmpty();
        verifyNoInteractions(conversations);
    }
}
