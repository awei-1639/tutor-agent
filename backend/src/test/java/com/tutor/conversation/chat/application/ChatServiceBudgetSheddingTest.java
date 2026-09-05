package com.tutor.conversation.chat.application;

import com.tutor.identity.auth.AuthContext;
import com.tutor.contract.Intent;
import com.tutor.agent.expert.IntentRouter;
import com.tutor.llm.BudgetExhausted;
import com.tutor.llm.BudgetPressureService;
import com.tutor.llm.LlmBudgetGuard;
import com.tutor.llm.LlmBusyException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预算压力降级与预算错误映射的服务级回归：
 * 降级必须砍质量特性而非可用性且留痕；预算/繁忙错误必须携带稳定机器码与
 * 用户文案，内部不变量消息不得透传。
 */
class ChatServiceBudgetSheddingTest {
    private final ChatServiceFixture fixture = new ChatServiceFixture();
    private final BudgetPressureService pressure = mock(BudgetPressureService.class);
    private final LlmBudgetGuard guard = mock(LlmBudgetGuard.class);
    private final RecordingEvents events = new RecordingEvents();

    @BeforeEach
    void setUp() {
        AuthContext.set(ChatServiceFixture.USER_ID);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    private ChatService service() {
        ChatService service = fixture.build();
        service.setBudgetGuard(guard);
        service.setBudgetPressure(pressure);
        return service;
    }

    @Test
    void elevatedPressureDisablesMultiHopAndLeavesTraceReason() {
        when(pressure.level()).thenReturn(BudgetPressureService.Level.ELEVATED);
        when(pressure.multiHopAllowed()).thenReturn(false);
        when(fixture.router.routeDecision(anyString(), any(), anyString()))
                .thenReturn(new IntentRouter.RouteDecision(
                        IntentRouter.Scope.IN_SCOPE, Intent.CHAT, List.of(), List.of(),
                        IntentRouter.RetrievalHint.MULTI_CANDIDATE, 0.95D, 0.95D, List.of(), false));

        service().turn(null, "hello", events);

        ArgumentCaptor<Boolean> multiHop = ArgumentCaptor.forClass(Boolean.class);
        verify(fixture.agenticRetriever).retrieveAdaptiveResult(anyString(), anyInt(), anyString(),
                multiHop.capture(), any(), any());
        assertThat(multiHop.getValue()).isFalse();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> snapshot = ArgumentCaptor.forClass(Map.class);
        verify(fixture.trace).span(anyString(), eq(ChatServiceFixture.CONVERSATION_ID), eq("router"),
                anyLong(), eq(true), snapshot.capture());
        assertThat(snapshot.getValue().get("budget_shed")).isEqualTo(true);
        assertThat((List<String>) snapshot.getValue().get("reason_codes"))
                .contains("BUDGET_MULTI_HOP_DISABLED");
    }

    @Test
    void normalPressureKeepsMultiHop() {
        when(pressure.level()).thenReturn(BudgetPressureService.Level.NORMAL);
        when(fixture.router.routeDecision(anyString(), any(), anyString()))
                .thenReturn(new IntentRouter.RouteDecision(
                        IntentRouter.Scope.IN_SCOPE, Intent.CHAT, List.of(), List.of(),
                        IntentRouter.RetrievalHint.MULTI_CANDIDATE, 0.95D, 0.95D, List.of(), false));

        service().turn(null, "hello", events);

        ArgumentCaptor<Boolean> multiHop = ArgumentCaptor.forClass(Boolean.class);
        verify(fixture.agenticRetriever).retrieveAdaptiveResult(anyString(), anyInt(), anyString(),
                multiHop.capture(), any(), any());
        assertThat(multiHop.getValue()).isTrue();
        verify(fixture.trace, org.mockito.Mockito.never()).span(anyString(),
                eq(ChatServiceFixture.CONVERSATION_ID), eq("router"), anyLong(), eq(true), any());
    }

    @Test
    void elevatedPressureCapsExpertFanOutToOne() {
        when(pressure.level()).thenReturn(BudgetPressureService.Level.ELEVATED);
        when(pressure.maxExperts()).thenReturn(1);
        when(fixture.router.routeDecision(anyString(), any(), anyString()))
                .thenReturn(new IntentRouter.RouteDecision(
                        IntentRouter.Scope.IN_SCOPE, Intent.RESUME, List.of(Intent.RESUME, Intent.INTERVIEW),
                        List.of(), IntentRouter.RetrievalHint.SINGLE, 0.95D, 0.95D, List.of(), false));
        fixture.stubExpertFanOut(List.of(), "融合回答");

        service().turn(null, "hello", events);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> experts = ArgumentCaptor.forClass(List.class);
        verify(fixture.expertRunner).run(experts.capture(), anyString(), anyString(), any(), any(), any());
        assertThat(experts.getValue()).hasSize(1);
        assertThat(events.done).hasSize(1);
    }

    @Test
    void budgetExhaustionSurfacesTypedCodeAndFriendlyCopy() {
        doThrow(new BudgetExhausted(BudgetExhausted.Kind.USER_DAILY, "用户每日 token 限额已用尽"))
                .when(guard).requireUserDailyAllowance(anyLong());

        service().turn(null, "hello", events);

        assertThat(events.errors).containsExactly(Map.entry(
                "budget_user_daily", "你今天的 AI 额度已用完，明天 0 点自动恢复。"));
    }

    @Test
    void concurrencyBusySurfacesRetryableCode() {
        fixture.routeAsDirectChat();
        doThrow(new LlmBusyException("LLM 并发队列已满，请稍后重试"))
                .when(fixture.gateway).chatStream(any(), any(), anyString(), any(), any());

        service().turn(null, "hello", events);

        assertThat(events.errors).containsExactly(Map.entry(
                "llm_busy", "LLM 并发队列已满，请稍后重试"));
    }

    @Test
    void internalInvariantMessagesDoNotLeakToUsers() {
        when(fixture.router.routeDecision(anyString(), any(), anyString())).thenReturn(null);

        service().turn(null, "hello", events);

        assertThat(events.errors).containsExactly(Map.entry(
                "TURN_FAILED", "服务异常, 请稍后重试"));
    }

    /** 记录 error(code, message) 与 done 事件的最小 TurnEvents 实现。 */
    private static final class RecordingEvents implements ChatTurnEvents {
        final List<Map.Entry<String, String>> errors = new CopyOnWriteArrayList<>();
        final List<String> done = new CopyOnWriteArrayList<>();

        @Override public void onMeta(long conversationId, String traceId) { }
        @Override public void onStage(String phase) { }
        @Override public void onCitations(List<com.tutor.contract.Evidence> evidences) { }
        @Override public void onToken(String token) { }
        @Override public void onClarify(String question) { }
        @Override public void onDone(long messageId, String fullText) { done.add(fullText); }
        @Override public void onError(String message) { errors.add(Map.entry("TURN_FAILED", message)); }
        @Override public void onError(String code, String message) { errors.add(Map.entry(code, message)); }
    }
}
