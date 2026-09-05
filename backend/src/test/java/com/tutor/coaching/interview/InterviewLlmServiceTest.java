package com.tutor.coaching.interview;

import com.tutor.contract.Purpose;
import com.tutor.llm.LlmGateway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InterviewLlmServiceTest {
    @Test
    void rejectsMissingOrUngroundedScorecardsInsteadOfInventingAMiddleScore() {
        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.chatJson(eq(Purpose.JUDGE), any(List.class), eq("trace"), isNull(), eq(1)))
                .thenReturn("{\"score\":5,\"confidence\":0.8,\"strengths\":[],\"missing_points\":[],\"evidence_quotes\":[]}");

        InterviewLlmService service = new InterviewLlmService(gateway);
        assertThatThrownBy(() -> service.scoreAnswer("q", "{}", "candidate answer", "trace"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("评分服务暂时不可用");
    }

    @Test
    void rejectsOutOfRangeScores() {
        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.chatJson(eq(Purpose.JUDGE), any(List.class), eq("trace"), isNull(), eq(1)))
                .thenReturn("{\"score\":11,\"confidence\":0.8,\"strengths\":[],\"missing_points\":[],\"evidence_quotes\":[\"candidate\"]}");

        InterviewLlmService service = new InterviewLlmService(gateway);
        assertThatThrownBy(() -> service.scoreAnswer("q", "{}", "candidate answer", "trace"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void acceptsGroundedScorecardThroughStructuredContract() {
        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.chatJson(eq(Purpose.JUDGE), any(List.class), eq("trace"), isNull(), eq(1)))
                .thenReturn("""
                        {"score":8,"strengths":["说明了缓存策略"],"missing_points":["补充监控"],
                         "confidence":0.85,"evidence_quotes":["缓存策略"]}
                        """);

        InterviewSession.Scorecard scorecard =
                new InterviewLlmService(gateway)
                        .scoreAnswer("q", "{}", "回答包含缓存策略", "trace");

        org.assertj.core.api.Assertions.assertThat(scorecard.score()).isEqualTo(8);
        org.assertj.core.api.Assertions.assertThat(scorecard.confidence()).isEqualTo(0.85D);
    }
}
