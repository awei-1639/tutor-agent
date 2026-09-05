package com.tutor.conversation.chat.feedback;

import com.tutor.conversation.chat.feedback.InternalFeedbackController;
import com.tutor.conversation.chat.feedback.MessageFeedbackService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalFeedbackControllerTest {
    @Test
    void returnsAggregatedFeedbackWithoutExposingUserData() {
        MessageFeedbackService service = mock(MessageFeedbackService.class);
        MessageFeedbackService.Summary expected = new MessageFeedbackService.Summary(
                4, 3, 1, List.of(new MessageFeedbackService.ReasonCount("citation_irrelevant", 1)), List.of(), List.of());
        when(service.summary()).thenReturn(expected);

        assertThat(new InternalFeedbackController(service).summary()).isEqualTo(expected);
    }
}
