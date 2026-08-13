package com.tutor.interview;

import com.tutor.contract.Purpose;
import com.tutor.llm.LlmGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InterviewLlmServiceTest {
    @Test
    void rejectsMissingOrUngroundedScorecardsInsteadOfInventingAMiddleScore() {
        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.chatJson(eq(Purpose.JUDGE), any(List.class), eq("trace")))
                .thenReturn("{\"score\":5,\"confidence\":0.8,\"strengths\":[],\"missing_points\":[],\"evidence_quotes\":[]}");

        InterviewLlmService service = new InterviewLlmService(gateway);
        assertThatThrownBy(() -> service.scoreAnswer("q", "{}", "candidate answer", "trace"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("评分服务暂时不可用");
    }

    @Test
    void rejectsOutOfRangeScores() {
        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.chatJson(eq(Purpose.JUDGE), any(List.class), eq("trace")))
                .thenReturn("{\"score\":11,\"confidence\":0.8,\"strengths\":[],\"missing_points\":[],\"evidence_quotes\":[\"candidate\"]}");

        InterviewLlmService service = new InterviewLlmService(gateway);
        assertThatThrownBy(() -> service.scoreAnswer("q", "{}", "candidate answer", "trace"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
