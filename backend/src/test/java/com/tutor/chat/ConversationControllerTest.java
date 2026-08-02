package com.tutor.chat;

import com.tutor.auth.AuthContext;
import com.tutor.memory.ConversationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ConversationControllerTest {
    private final ConversationStore store = mock(ConversationStore.class);
    private final ConversationController controller = new ConversationController(store);

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void listUsesAuthenticatedUserInsteadOfRequestParameter() {
        AuthContext.set(42L);
        when(store.listConversations(42L)).thenReturn(List.of());

        assertThat(controller.list()).isEmpty();
        verify(store).listConversations(42L);
    }

    @Test
    void messagesHideForeignConversationAsNotFound() {
        AuthContext.set(42L);
        when(store.belongsToUser(9L, 42L)).thenReturn(false);

        assertThatThrownBy(() -> controller.messages(9L, 200))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(404);
        verify(store, never()).recentMessagesForUser(anyLong(), anyLong(), anyInt());
    }
}
