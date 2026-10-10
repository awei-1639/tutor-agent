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

    @Test
    void answersCandidateQuestionAsTheInterviewer() {
        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.chatJson(eq(Purpose.CHAT), any(List.class), eq("trace"), isNull(), eq(1)))
                .thenReturn("""
                        {"reply":"团队现在在做缓存一致性治理，新人会先跟一位 Owner 熟悉链路。",
                         "tip":"问到了团队当前的技术挑战，信息量高。"}
                        """);

        InterviewLlmService.CandidateAnswer answer = new InterviewLlmService(gateway)
                .answerCandidateQuestion("后端开发", "要求 Redis 经验", "Redis", "团队现在最棘手的问题是什么？", "trace");

        org.assertj.core.api.Assertions.assertThat(answer.reply()).contains("缓存一致性");
        org.assertj.core.api.Assertions.assertThat(answer.tip()).contains("信息量高");
    }

    @Test
    void fallsBackWhenTheCandidateAnswerIsUnusable() {
        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.chatJson(eq(Purpose.CHAT), any(List.class), eq("trace"), isNull(), eq(1)))
                .thenReturn("这不是 JSON");

        InterviewLlmService.CandidateAnswer answer = new InterviewLlmService(gateway)
                .answerCandidateQuestion("后端开发", "", "Redis", "团队技术栈是什么？", "trace");

        org.assertj.core.api.Assertions.assertThat(answer.reply()).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(answer.tip()).isNotBlank();
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

    @Test
    void scorecardParsesDimensionSubscoresFromStoredJson() {
        String json = "{\"score\":8,\"strengths\":[\"讲清了原理\"],\"missing_points\":[\"未提边界\"],\"confidence\":0.8,"
                + "\"rubricVersion\":\"interview-scorecard-v2\","
                + "\"dimensions\":{\"technical_depth\":9,\"structure\":7,\"boundary_awareness\":4,\"role_fit\":8,\"unknown_dim\":10}}";

        InterviewSession.Scorecard card = new InterviewLlmService(mock(LlmGateway.class)).scorecard(json);

        org.assertj.core.api.Assertions.assertThat(card.dimensions())
                .containsExactly(org.assertj.core.data.MapEntry.entry("technical_depth", 9),
                        org.assertj.core.data.MapEntry.entry("structure", 7),
                        org.assertj.core.data.MapEntry.entry("boundary_awareness", 4),
                        org.assertj.core.data.MapEntry.entry("role_fit", 8));
    }

    @Test
    void scoreAnswerCarriesDimensionSubscores() {
        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.chatJson(eq(Purpose.JUDGE), any(List.class), eq("trace"), isNull(), eq(1)))
                .thenReturn("""
                        {"score":8,"strengths":["讲清了 HNSW 的构建过程"],"missing_points":["未提删除场景"],
                         "confidence":0.8,"evidence_quotes":["HNSW 的构建过程"],
                         "dimensions":{"technical_depth":9,"structure":7,"boundary_awareness":4,"role_fit":8},
                         "boundary_note":"主动说明未在生产验证过删除场景"}
                        """);

        InterviewSession.Scorecard card = new InterviewLlmService(gateway)
                .scoreAnswer("向量索引怎么选?", "{}", "HNSW 的构建过程是这样的…", "trace");

        org.assertj.core.api.Assertions.assertThat(card.dimensions()).containsEntry("technical_depth", 9)
                .containsEntry("boundary_awareness", 4);
        org.assertj.core.api.Assertions.assertThat(card.rubricVersion()).isEqualTo("interview-scorecard-v2");
    }

    @Test
    void chineseDimensionKeysAreMappedBackToCanonicalNames() {
        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.chatJson(eq(Purpose.JUDGE), any(List.class), eq("trace"), isNull(), eq(1)))
                .thenReturn("""
                        {"score":6,"strengths":["讲了流程"],"missing_points":["缺边界"],"confidence":0.6,
                         "evidence_quotes":["讲了流程"],
                         "dimensions":{"技术深度":7,"结构与表达":6,"边界意识":3,"岗位匹配度":6}}
                        """);

        InterviewSession.Scorecard card = new InterviewLlmService(gateway)
                .scoreAnswer("问题?", "{}", "讲了流程", "trace");

        org.assertj.core.api.Assertions.assertThat(card.dimensions())
                .containsExactly(org.assertj.core.data.MapEntry.entry("technical_depth", 7),
                        org.assertj.core.data.MapEntry.entry("structure", 6),
                        org.assertj.core.data.MapEntry.entry("boundary_awareness", 3),
                        org.assertj.core.data.MapEntry.entry("role_fit", 6));
    }

    @Test
    void scorecardWithoutDimensionsStaysUsable() {
        InterviewSession.Scorecard card = new InterviewLlmService(mock(LlmGateway.class))
                .scorecard("{\"score\":5,\"strengths\":[],\"missing_points\":[],\"confidence\":0.5}");

        org.assertj.core.api.Assertions.assertThat(card.score()).isEqualTo(5);
        org.assertj.core.api.Assertions.assertThat(card.dimensions()).isNull();
    }
}
