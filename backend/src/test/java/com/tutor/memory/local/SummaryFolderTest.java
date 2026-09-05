package com.tutor.memory.local;

import com.tutor.contract.Purpose;
import com.tutor.llm.LlmMessage;
import com.tutor.llm.LlmGateway;
import com.tutor.memory.local.ConversationStore;
import com.tutor.memory.local.SummaryFolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L1 滚动摘要折叠 — 触发条件与异常隔离测试。
 * 不测 LLM 内容, 只测: ①折叠阈值边界 ②LLM 异常不抛 ③正常路径正确调用 chatJson+saveSummary。
 */
@ExtendWith(MockitoExtension.class)
class SummaryFolderTest {

    @Mock ConversationStore store;
    @Mock LlmGateway gateway;

    SummaryFolder folder;

    @BeforeEach
    void setUp() {
        folder = new SummaryFolder(store, gateway);
    }

    @Test
    @DisplayName("窗口外消息不足阈值 → 不调用 LLM")
    void belowThreshold_skipsLlm() {
        // 触发线 = FOLD_TRIGGER - KEEP_RECENT = 20 - 12 = 8 条
        when(store.summaryState(1L)).thenReturn(new ConversationStore.SummaryState(null, 0L));
        when(store.messagesToFold(eq(1L), eq(0L), eq(SummaryFolder.KEEP_RECENT_MESSAGES)))
                .thenReturn(List.of(new ConversationStore.Msg("user", "x"))); // 1 < 8

        folder.maybeFold(1L, "trace-1");

        verify(gateway, never()).chatJson(any(), any(), anyString(), isNull(), eq(1));
        verify(store, never()).saveSummary(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("窗口外消息达到阈值 → 调用 LLM 并落库")
    void atThreshold_callsLlmAndSaves() {
        when(store.summaryState(7L)).thenReturn(new ConversationStore.SummaryState(null, 0L));
        List<ConversationStore.Msg> toFold = List.of(
                new ConversationStore.Msg("user", "a"),
                new ConversationStore.Msg("assistant", "b"),
                new ConversationStore.Msg("user", "c"),
                new ConversationStore.Msg("assistant", "d"),
                new ConversationStore.Msg("user", "e"),
                new ConversationStore.Msg("assistant", "f"),
                new ConversationStore.Msg("user", "g"),
                new ConversationStore.Msg("assistant", "h")
        ); // 8 条
        when(store.messagesToFold(eq(7L), eq(0L), eq(12))).thenReturn(toFold);
        when(store.maxFoldableMsgId(eq(7L), eq(12))).thenReturn(123L);
        when(gateway.chatJson(eq(Purpose.SUMMARY), any(), eq("trace-x"), isNull(), eq(1)))
                .thenReturn("{\"summary\":\"用户关注NLP方向\"}");

        folder.maybeFold(7L, "trace-x");

        verify(gateway, times(1)).chatJson(eq(Purpose.SUMMARY), any(), eq("trace-x"), isNull(), eq(1));
        verify(store, times(1)).saveSummary(eq(7L), eq("用户关注NLP方向"), eq(123L));
    }

    @Test
    @DisplayName("已有摘要 → 拼接到 prompt 前缀")
    @SuppressWarnings("unchecked") // List.class 不携带 ChatMessage 泛型信息；仅用于 Mockito 参数捕获。
    void existingSummaryPrefixesPrompt() {
        when(store.summaryState(2L)).thenReturn(new ConversationStore.SummaryState("旧摘要", 10L));
        List<ConversationStore.Msg> eight = IntStream.range(0, 8)
                .mapToObj(i -> new ConversationStore.Msg("user", "msg" + i)).toList();
        when(store.messagesToFold(eq(2L), eq(10L), eq(12))).thenReturn(eight);
        when(store.maxFoldableMsgId(eq(2L), eq(12))).thenReturn(99L);
        when(gateway.chatJson(eq(Purpose.SUMMARY), any(), anyString(), isNull(), eq(1)))
                .thenReturn("{\"summary\":\"合并后摘要\"}");

        folder.maybeFold(2L, "t");

        ArgumentCaptor<List<LlmMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(gateway).chatJson(eq(Purpose.SUMMARY), captor.capture(), anyString(), isNull(), eq(1));
        assertThat(captor.getValue().toString()).contains("旧摘要");
    }

    @Test
    @DisplayName("LLM 抛异常 → 折叠失败被吞, 不影响主对话")
    void llmFailure_swallowedSilently() {
        when(store.summaryState(3L)).thenReturn(new ConversationStore.SummaryState(null, 0L));
        List<ConversationStore.Msg> eight = IntStream.range(0, 8)
                .mapToObj(i -> new ConversationStore.Msg("user", "m" + i)).toList();
        when(store.messagesToFold(eq(3L), eq(0L), eq(12))).thenReturn(eight);
        when(gateway.chatJson(any(), any(), anyString(), isNull(), eq(1)))
                .thenThrow(new RuntimeException("网络超时"));

        // 不抛异常
        assertThatCode(() -> folder.maybeFold(3L, "t")).doesNotThrowAnyException();
        verify(store, never()).saveSummary(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("LLM 返回空 summary → 不落库")
    void emptySummaryNotSaved() {
        when(store.summaryState(4L)).thenReturn(new ConversationStore.SummaryState(null, 0L));
        List<ConversationStore.Msg> eight = IntStream.range(0, 8)
                .mapToObj(i -> new ConversationStore.Msg("user", "m" + i)).toList();
        when(store.messagesToFold(eq(4L), eq(0L), eq(12))).thenReturn(eight);
        when(gateway.chatJson(any(), any(), anyString(), isNull(), eq(1))).thenReturn("{\"summary\":\"\"}");

        folder.maybeFold(4L, "t");
        verify(store, never()).saveSummary(anyLong(), anyString(), anyLong());
    }
}
