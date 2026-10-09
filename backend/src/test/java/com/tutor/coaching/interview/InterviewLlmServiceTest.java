package com.tutor.coaching.interview;

import com.tutor.contract.Purpose;
import com.tutor.platform.llm.LlmGateway;
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

    private static LlmGateway questionGateway() {
        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.chatJson(eq(Purpose.PLAN), any(List.class), eq("trace"), isNull(), eq(1)))
                .thenReturn("""
                        {"question":"Faiss 向量检索在召回链路里怎么选型?","required_points":["说明索引类型取舍"],
                         "bonus_points":["说明召回率与延迟的权衡"],"critical_errors":["混淆ANN与精确 Top-K"]}
                        """);
        return gateway;
    }

    @Test
    void injectsCareerGapContextIntoQuestionPrompt() {
        LlmGateway gateway = questionGateway();
        @SuppressWarnings("unchecked") org.mockito.ArgumentCaptor<List<com.tutor.platform.llm.LlmMessage>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);

        InterviewSession.QuestionSpec spec = new InterviewLlmService(gateway)
                .generateQuestion("向量检索", "算法工程师", "", "technical", "MID", 1, "trace",
                        List.of(), "已掌握: Python; 尚缺: FAISS; 需强化: PyMilvus");

        org.mockito.Mockito.verify(gateway).chatJson(eq(Purpose.PLAN), captor.capture(), eq("trace"), isNull(), eq(1));
        String prompt = captor.getValue().toString();
        org.assertj.core.api.Assertions.assertThat(prompt).contains("候选人画像差距").contains("FAISS");
        org.assertj.core.api.Assertions.assertThat(spec.question()).isNotBlank();
    }

    @Test
    void omitsGapSectionWhenNoGapData() {
        LlmGateway gateway = questionGateway();
        @SuppressWarnings("unchecked") org.mockito.ArgumentCaptor<List<com.tutor.platform.llm.LlmMessage>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);

        new InterviewLlmService(gateway).generateQuestion("向量检索", "算法工程师", "", "technical", "MID", 1,
                "trace", List.of(), null);

        org.mockito.Mockito.verify(gateway).chatJson(eq(Purpose.PLAN), captor.capture(), eq("trace"), isNull(), eq(1));
        org.assertj.core.api.Assertions.assertThat(captor.getValue().toString()).doesNotContain("候选人画像差距");
    }
}
