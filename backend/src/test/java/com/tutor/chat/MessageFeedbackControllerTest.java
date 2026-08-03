package com.tutor.chat;

import com.tutor.auth.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MessageFeedbackControllerTest {
    private final MessageFeedbackService service = mock(MessageFeedbackService.class);
    private final MessageFeedbackController controller = new MessageFeedbackController(service);

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void feedbackIsBoundToAuthenticatedUser() {
        AuthContext.set(42L);
        MessageFeedbackService.Feedback expected = new MessageFeedbackService.Feedback(3L, 9L, "helpful", null, "trace-x");
        when(service.save(42L, 9L, "helpful", null)).thenReturn(expected);

        assertThat(controller.submit(new MessageFeedbackController.SubmitRequest(9L, "helpful", null))).isEqualTo(expected);
        verify(service).save(42L, 9L, "helpful", null);
    }

    @Test
    void foreignOrMissingMessageIsHiddenAsNotFound() {
        AuthContext.set(42L);
        when(service.save(42L, 9L, "not_helpful", "citation_irrelevant")).thenReturn(null);

        assertThatThrownBy(() -> controller.submit(new MessageFeedbackController.SubmitRequest(9L, "not_helpful", "citation_irrelevant")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(404);
    }
}
