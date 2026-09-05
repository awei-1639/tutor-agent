package com.tutor.conversation.chat.application;

import com.tutor.identity.auth.AuthContext;
import com.tutor.contract.CancellationToken;
import com.tutor.contract.Evidence;
import com.tutor.platform.llm.LlmStreamHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 钉住 turn() 各条路径的可观察行为，为后续把 ChatService 拆成阶段化协作者提供回归网。
 * 断言集中在对外契约（SSE 事件、落库、后台任务、是否调用检索/专家），不绑定内部实现细节。
 */
class ChatServiceTurnPathsTest {
    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void outOfScopeSkipsRetrievalEntirely() {
        ChatServiceFixture fixture = new ChatServiceFixture();
        fixture.routeAsOutOfScope();
        streamAnswer(fixture, "越界回答");
        AuthContext.set(ChatServiceFixture.USER_ID);

        RecordingEvents events = new RecordingEvents();
        fixture.build().turn(null, "今天天气如何", events);

        verify(fixture.agenticRetriever, never())
                .retrieveAdaptiveResult(anyString(), anyInt(), anyString(), any(Boolean.class), any(), any());
        verify(fixture.longTermMemory, never()).recall(anyLong(), anyString(), anyString());
        // Badcase 06 教训: 跳过检索也要留痕, 否则降级/越界在指标里是静默的
        verify(fixture.trace).span(anyString(), any(), eq("retrieve"), anyLong(), eq(false),
                argThat(snapshot -> Boolean.TRUE.equals(snapshot.get("skipped"))));
        assertThat(events.stages).doesNotContain("retrieving");
        assertThat(events.done).isTrue();
    }

    @Test
    void directChatRetrievesEmitsCitationsAndPersists() {
        ChatServiceFixture fixture = new ChatServiceFixture();
        fixture.routeAsDirectChat();
        fixture.stubRetrieval(List.of(evidence("skill:rag")));
        streamAnswer(fixture, "RAG 是检索增强生成");
        AuthContext.set(ChatServiceFixture.USER_ID);

        RecordingEvents events = new RecordingEvents();
        fixture.build().turn(null, "什么是 RAG", events);

        assertThat(events.stages).contains("retrieving");
        assertThat(events.citationCount).isEqualTo(1);
        assertThat(events.tokens).contains("RAG 是检索增强生成");
        assertThat(events.done).isTrue();
        verify(fixture.conversations).appendMessage(eq(ChatServiceFixture.CONVERSATION_ID), eq("assistant"),
                eq("RAG 是检索增强生成"), anyString(), any(), anyString(), anyInt(), anyString(), any());
    }

    @Test
    void directChatSchedulesPostTurnWorkWithTheRequestUser() {
        ChatServiceFixture fixture = new ChatServiceFixture();
        fixture.routeAsDirectChat();
        streamAnswer(fixture, "回答");
        AuthContext.set(ChatServiceFixture.USER_ID);

        fixture.build().turn(null, "hello", new RecordingEvents());

        // 后台任务在另一个线程执行，必须使用请求线程固定下来的身份。
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(fixture.profiles).updateFromMessage(eq(ChatServiceFixture.USER_ID), eq("hello"),
                    anyString(), anyLong());
            verify(fixture.episodeSummarizer).maybeSummarize(eq(ChatServiceFixture.CONVERSATION_ID),
                    eq(ChatServiceFixture.USER_ID), anyString(), anyLong());
        });
    }

    @Test
    void alreadyCancelledTurnDoesNothing() {
        ChatServiceFixture fixture = new ChatServiceFixture();
        AuthContext.set(ChatServiceFixture.USER_ID);
        CancellationToken cancelled = new CancellationToken();
        cancelled.cancel();

        RecordingEvents events = new RecordingEvents();
        fixture.build().turn(null, "hello", events, cancelled);

        verify(fixture.router, never()).routeDecision(anyString(), any(), anyString());
        verify(fixture.conversations, never()).ensureConversation(any(), anyLong());
        assertThat(events.metaCalls).isZero();
    }

    @Test
    void streamingErrorSurfacesUserFacingMessageAndSkipsPersistence() {
        ChatServiceFixture fixture = new ChatServiceFixture();
        fixture.routeAsDirectChat();
        doAnswer(invocation -> {
            invocation.<LlmStreamHandler>getArgument(3)
                    .onError(new IllegalStateException("provider down"));
            return null;
        }).when(fixture.gateway).chatStream(any(), any(), anyString(), any(), any(CancellationToken.class));
        AuthContext.set(ChatServiceFixture.USER_ID);

        RecordingEvents events = new RecordingEvents();
        fixture.build().turn(null, "hello", events);

        assertThat(events.errors).containsExactly("生成失败, 请稍后重试");
        assertThat(events.done).isFalse();
        verify(fixture.conversations, never()).appendMessage(anyLong(), eq("assistant"), anyString(), any(), any(),
                anyString(), anyInt(), any(), any());
    }

    @Test
    void routerFailureReportsAServiceErrorWithoutLeakingDetails() {
        ChatServiceFixture fixture = new ChatServiceFixture();
        org.mockito.Mockito.when(fixture.router.routeDecision(anyString(), any(), anyString()))
                .thenThrow(new RuntimeException("boom: internal detail"));
        AuthContext.set(ChatServiceFixture.USER_ID);

        RecordingEvents events = new RecordingEvents();
        fixture.build().turn(null, "hello", events);

        assertThat(events.errors).containsExactly("服务异常, 请稍后重试");
        assertThat(events.errors.getFirst()).doesNotContain("internal detail");
    }

    @Test
    void resumeIntentFansOutToExpertsAndAggregates() {
        ChatServiceFixture fixture = new ChatServiceFixture();
        fixture.routeAsResumeExpert();
        fixture.stubRetrieval(List.of(evidence("skill:resume")));
        fixture.stubExpertFanOut(
                List.of(new com.tutor.contract.ExpertOutput("resume", "建议正文", 0.9D, List.of("skill:resume"))),
                "综合建议");
        AuthContext.set(ChatServiceFixture.USER_ID);

        RecordingEvents events = new RecordingEvents();
        fixture.build().turn(null, "帮我看看简历", events);

        assertThat(events.stages).contains("expert:resume", "aggregating");
        assertThat(events.tokens).contains("综合建议");
        assertThat(events.done).isTrue();
        // 专家路径不得退回直答流式，否则会出现两份回答。
        verify(fixture.gateway, never()).chatStream(any(), any(), anyString(), any(), any(CancellationToken.class));
    }

    @Test
    void cancellingBeforeAggregationStopsWithoutPersisting() {
        ChatServiceFixture fixture = new ChatServiceFixture();
        fixture.routeAsResumeExpert();
        CancellationToken cancellation = new CancellationToken();
        org.mockito.Mockito.when(fixture.expertRunner.buildBriefing(anyString(), any(), anyString()))
                .thenReturn(new com.tutor.agent.expert.ExpertRunner.Briefing("briefing", java.util.Set.of()));
        // 专家执行期间用户断开 SSE：仲裁不应再启动，也不应落库。
        org.mockito.Mockito.when(fixture.expertRunner.run(any(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    cancellation.cancel();
                    return List.of(new com.tutor.contract.ExpertOutput("resume", "x", 0.9D, List.of()));
                });
        AuthContext.set(ChatServiceFixture.USER_ID);

        RecordingEvents events = new RecordingEvents();
        fixture.build().turn(null, "帮我看看简历", events, cancellation);

        verify(fixture.aggregator, never())
                .aggregateStream(any(), anyString(), anyString(), anyString(), any(), any());
        verify(fixture.conversations, never()).appendMessage(anyLong(), eq("assistant"), anyString(), any(), any(),
                anyString(), anyInt(), any(), any());
        assertThat(events.done).isFalse();
    }

    @Test
    void toolLoopAnswersWithoutStreamingWhenEnabled() {
        ChatServiceFixture fixture = new ChatServiceFixture();
        fixture.routeAsDirectChat();
        com.tutor.agent.tool.ToolCallLoop loop = org.mockito.Mockito.mock(com.tutor.agent.tool.ToolCallLoop.class);
        org.mockito.Mockito.when(loop.run(any(), any(), anyString(), any()))
                .thenReturn(new com.tutor.agent.tool.ToolCallLoop.LoopResult("工具回答", 1, List.of("retrieve")));
        AuthContext.set(ChatServiceFixture.USER_ID);

        RecordingEvents events = new RecordingEvents();
        fixture.buildWithToolLoop(loop, true).turn(null, "hello", events);

        assertThat(events.done).isTrue();
        verify(fixture.gateway, never()).chatStream(any(), any(), anyString(), any(), any(CancellationToken.class));
        verify(fixture.conversations).appendMessage(eq(ChatServiceFixture.CONVERSATION_ID), eq("assistant"),
                eq("工具回答"), anyString(), any(), anyString(), anyInt(), anyString(), any());
    }

    @Test
    void toolLoopFailureFallsBackToStreaming() {
        ChatServiceFixture fixture = new ChatServiceFixture();
        fixture.routeAsDirectChat();
        streamAnswer(fixture, "流式兜底回答");
        com.tutor.agent.tool.ToolCallLoop loop = org.mockito.Mockito.mock(com.tutor.agent.tool.ToolCallLoop.class);
        org.mockito.Mockito.when(loop.run(any(), any(), anyString(), any()))
                .thenThrow(new IllegalStateException("loop exceeded steps"));
        AuthContext.set(ChatServiceFixture.USER_ID);

        RecordingEvents events = new RecordingEvents();
        fixture.buildWithToolLoop(loop, true).turn(null, "hello", events);

        assertThat(events.tokens).contains("流式兜底回答");
        assertThat(events.done).isTrue();
        assertThat(events.errors).isEmpty();
    }

    private static void streamAnswer(ChatServiceFixture fixture, String answer) {
        doAnswer(invocation -> {
            LlmStreamHandler handler = invocation.getArgument(3);
            handler.onToken(answer);
            handler.onComplete(new com.tutor.platform.llm.LlmStreamResult("test", 0, 0, false));
            return null;
        }).when(fixture.gateway).chatStream(any(), any(), anyString(), any(), any(CancellationToken.class));
    }

    private static Evidence evidence(String nodeId) {
        return new Evidence(nodeId, "skill", "证据文本", 0.9D, null, null, null, null);
    }

    private static final class RecordingEvents implements ChatTurnEvents {
        final List<String> stages = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final StringBuilder tokens = new StringBuilder();
        int citationCount;
        int metaCalls;
        boolean done;

        @Override public void onMeta(long conversationId, String traceId) { metaCalls++; }
        @Override public void onStage(String phase) { stages.add(phase); }
        @Override public void onCitations(List<Evidence> evidences) { citationCount = evidences.size(); }
        @Override public void onToken(String token) { tokens.append(token); }
        @Override public void onClarify(String question) { }
        @Override public void onDone(long messageId, String fullText) { done = true; }
        @Override public void onError(String message) { errors.add(message); }
    }
}
