package com.tutor.conversation.memory.local;

import com.tutor.llm.LlmGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EpisodeSummarizerTest {
    @Test
    void doesNotCallLlmBeforeConfiguredMessageThreshold() {
        LlmGateway gateway = mock(LlmGateway.class);
        EpisodeStore episodes = mock(EpisodeStore.class);
        ConversationStore conversations = mock(ConversationStore.class);
        EpisodeCommitter committer = mock(EpisodeCommitter.class);
        com.tutor.conversation.memory.policy.MemoryAdmissionPolicy admission = mock(com.tutor.conversation.memory.policy.MemoryAdmissionPolicy.class);
        when(conversations.episodeUptoMsgId(1L)).thenReturn(10L);
        when(conversations.messagesAfter(1L, 10L, 30)).thenReturn(List.of(
                new ConversationStore.Msg(11, "user", "a"), new ConversationStore.Msg(12, "assistant", "b")));

        new EpisodeSummarizer(gateway, gateway, episodes, conversations, committer, admission)
                .maybeSummarize(1L, 7L, "trace");

        verifyNoInteractions(gateway, committer, admission);
    }

    @Test
    void advancesWatermarkOnlyAfterAnEpisodeIsPersisted() {
        LlmGateway gateway = mock(LlmGateway.class);
        EpisodeStore episodes = mock(EpisodeStore.class);
        ConversationStore conversations = mock(ConversationStore.class);
        EpisodeCommitter committer = mock(EpisodeCommitter.class);
        com.tutor.conversation.memory.policy.MemoryAdmissionPolicy admission = mock(com.tutor.conversation.memory.policy.MemoryAdmissionPolicy.class);
        when(conversations.episodeUptoMsgId(1L)).thenReturn(10L);
        when(conversations.messagesAfter(1L, 10L, 30)).thenReturn(List.of(
                new ConversationStore.Msg(11, "user", "我想准备a"), new ConversationStore.Msg(12, "assistant", "b"),
                new ConversationStore.Msg(13, "user", "我想准备c"), new ConversationStore.Msg(14, "assistant", "d"),
                new ConversationStore.Msg(15, "user", "我想准备e"), new ConversationStore.Msg(16, "assistant", "f"),
                new ConversationStore.Msg(17, "user", "我想准备g"), new ConversationStore.Msg(18, "assistant", "h"),
                new ConversationStore.Msg(19, "user", "我想准备i"), new ConversationStore.Msg(20, "assistant", "j"),
                new ConversationStore.Msg(21, "user", "我想准备k"), new ConversationStore.Msg(22, "assistant", "l")));
        when(gateway.chatJson(any(), any(), any(), isNull(), eq(1)))
                .thenReturn("{\"summary\":\"summary\",\"topics\":[\"topic\"],\"open_items\":[]}");
        when(gateway.embed(any(), any())).thenReturn(new float[]{0.1f});
        when(admission.acceptsEpisode(any(), any(), any())).thenReturn(true);
        when(committer.commitReturningId(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
                any(), any(), any(), any(), anyLong())).thenReturn(55L);

        new EpisodeSummarizer(gateway, gateway, episodes, conversations, committer, admission).maybeSummarize(1L, 7L, "trace");

        verify(committer).commitReturningId(eq(7L), eq(1L), eq(10L), eq(11L), eq(22L),
                eq("summary"), any(), any(), any(), eq(Long.MIN_VALUE));
        verify(episodes, never()).insert(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void triggersFactExtractionOnlyAfterEpisodeCommit() {
        LlmGateway gateway = mock(LlmGateway.class);
        EpisodeStore episodes = mock(EpisodeStore.class);
        ConversationStore conversations = mock(ConversationStore.class);
        EpisodeCommitter committer = mock(EpisodeCommitter.class);
        com.tutor.conversation.memory.policy.MemoryAdmissionPolicy admission = mock(com.tutor.conversation.memory.policy.MemoryAdmissionPolicy.class);
        FactExtractionService factExtraction = mock(FactExtractionService.class);
        when(conversations.episodeUptoMsgId(1L)).thenReturn(10L);
        when(conversations.messagesAfter(1L, 10L, 30)).thenReturn(List.of(
                new ConversationStore.Msg(11, "user", "我想准备a"), new ConversationStore.Msg(12, "assistant", "b"),
                new ConversationStore.Msg(13, "user", "我想准备c"), new ConversationStore.Msg(14, "assistant", "d"),
                new ConversationStore.Msg(15, "user", "我想准备e"), new ConversationStore.Msg(16, "assistant", "f"),
                new ConversationStore.Msg(17, "user", "我想准备g"), new ConversationStore.Msg(18, "assistant", "h"),
                new ConversationStore.Msg(19, "user", "我想准备i"), new ConversationStore.Msg(20, "assistant", "j"),
                new ConversationStore.Msg(21, "user", "我想准备k"), new ConversationStore.Msg(22, "assistant", "l")));
        when(gateway.chatJson(any(), any(), any(), isNull(), eq(1)))
                .thenReturn("{\"summary\":\"summary\",\"topics\":[\"topic\"],\"open_items\":[]}");
        when(gateway.embed(any(), any())).thenReturn(new float[]{0.1f});
        when(admission.acceptsEpisode(any(), any(), any())).thenReturn(true);
        when(committer.commitReturningId(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
                any(), any(), any(), any(), anyLong())).thenReturn(55L);

        new EpisodeSummarizer(gateway, gateway, episodes, conversations, committer, admission,
                12, new com.tutor.llm.structured.StructuredOutputService(gateway, null), factExtraction,
                new com.tutor.conversation.memory.policy.MemoryImportanceGate(), true)
                .maybeSummarize(1L, 7L, "trace");

        verify(factExtraction).extractFromWindow(eq(7L), eq(55L), eq(Long.MIN_VALUE), any(), eq("trace"));

        when(committer.commitReturningId(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
                any(), any(), any(), any(), anyLong())).thenReturn(0L);
        new EpisodeSummarizer(gateway, gateway, episodes, conversations, committer, admission,
                12, new com.tutor.llm.structured.StructuredOutputService(gateway, null), factExtraction,
                new com.tutor.conversation.memory.policy.MemoryImportanceGate(), true)
                .maybeSummarize(1L, 7L, "trace2");
        verify(factExtraction, times(1)).extractFromWindow(anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void gateBlocksLlmForSalienceFreeWindows() {
        LlmGateway gateway = mock(LlmGateway.class);
        EpisodeStore episodes = mock(EpisodeStore.class);
        ConversationStore conversations = mock(ConversationStore.class);
        EpisodeCommitter committer = mock(EpisodeCommitter.class);
        com.tutor.conversation.memory.policy.MemoryAdmissionPolicy admission = mock(com.tutor.conversation.memory.policy.MemoryAdmissionPolicy.class);
        when(conversations.episodeUptoMsgId(1L)).thenReturn(10L);
        when(conversations.messagesAfter(1L, 10L, 30)).thenReturn(List.of(
                new ConversationStore.Msg(11, "user", "哈哈"), new ConversationStore.Msg(12, "assistant", "b"),
                new ConversationStore.Msg(13, "user", "好的"), new ConversationStore.Msg(14, "assistant", "d"),
                new ConversationStore.Msg(15, "user", "嗯嗯"), new ConversationStore.Msg(16, "assistant", "f"),
                new ConversationStore.Msg(17, "user", "有意思"), new ConversationStore.Msg(18, "assistant", "h"),
                new ConversationStore.Msg(19, "user", "先这样"), new ConversationStore.Msg(20, "assistant", "j"),
                new ConversationStore.Msg(21, "user", "收到"), new ConversationStore.Msg(22, "assistant", "l")));

        new EpisodeSummarizer(gateway, gateway, episodes, conversations, committer, admission)
                .maybeSummarize(1L, 7L, "trace");

        verify(gateway, never()).chatJson(any(), any(), any(), any(), anyInt());
        verify(committer, never()).commitReturningId(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
                any(), any(), any(), any(), anyLong());
        // 窗口未满：不推进水位线，后续消息累积后同一批仍会重新参与判定。
        verify(conversations, never()).advanceEpisodeWatermark(anyLong(), anyLong());
    }

    @Test
    void gateAdvancesWatermarkWhenAFullWindowHasNoSalientSignal() {
        LlmGateway gateway = mock(LlmGateway.class);
        EpisodeStore episodes = mock(EpisodeStore.class);
        ConversationStore conversations = mock(ConversationStore.class);
        EpisodeCommitter committer = mock(EpisodeCommitter.class);
        com.tutor.conversation.memory.policy.MemoryAdmissionPolicy admission = mock(com.tutor.conversation.memory.policy.MemoryAdmissionPolicy.class);
        java.util.List<ConversationStore.Msg> fullWindow = java.util.stream.IntStream
                .range(0, EpisodeSummarizer.MAX_WINDOW_MESSAGES)
                .mapToObj(i -> new ConversationStore.Msg(11 + i, i % 2 == 0 ? "user" : "assistant", "嗯嗯"))
                .toList();
        when(conversations.episodeUptoMsgId(1L)).thenReturn(10L);
        when(conversations.messagesAfter(1L, 10L, EpisodeSummarizer.MAX_WINDOW_MESSAGES)).thenReturn(fullWindow);

        new EpisodeSummarizer(gateway, gateway, episodes, conversations, committer, admission)
                .maybeSummarize(1L, 7L, "trace");

        verify(gateway, never()).chatJson(any(), any(), any(), any(), anyInt());
        // 窗口已满且无显著信号：跳过整窗并推进水位线，否则该会话永远读到同一批消息。
        verify(conversations).advanceEpisodeWatermark(1L, fullWindow.getLast().id);
    }

    @Test
    void gateCanBeDisabledToKeepLegacyBehavior() {
        LlmGateway gateway = mock(LlmGateway.class);
        EpisodeStore episodes = mock(EpisodeStore.class);
        ConversationStore conversations = mock(ConversationStore.class);
        EpisodeCommitter committer = mock(EpisodeCommitter.class);
        com.tutor.conversation.memory.policy.MemoryAdmissionPolicy admission = mock(com.tutor.conversation.memory.policy.MemoryAdmissionPolicy.class);
        when(conversations.episodeUptoMsgId(1L)).thenReturn(10L);
        when(conversations.messagesAfter(1L, 10L, 30)).thenReturn(List.of(
                new ConversationStore.Msg(11, "user", "哈哈"), new ConversationStore.Msg(12, "assistant", "b"),
                new ConversationStore.Msg(13, "user", "好的"), new ConversationStore.Msg(14, "assistant", "d"),
                new ConversationStore.Msg(15, "user", "嗯嗯"), new ConversationStore.Msg(16, "assistant", "f"),
                new ConversationStore.Msg(17, "user", "有意思"), new ConversationStore.Msg(18, "assistant", "h"),
                new ConversationStore.Msg(19, "user", "先这样"), new ConversationStore.Msg(20, "assistant", "j"),
                new ConversationStore.Msg(21, "user", "收到"), new ConversationStore.Msg(22, "assistant", "l")));
        when(gateway.chatJson(any(), any(), any(), isNull(), eq(1)))
                .thenReturn("{\"summary\":\"summary\",\"topics\":[\"topic\"],\"open_items\":[]}");
        when(gateway.embed(any(), any())).thenReturn(new float[]{0.1f});
        when(admission.acceptsEpisode(any(), any(), any())).thenReturn(true);
        when(committer.commitReturningId(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
                any(), any(), any(), any(), anyLong())).thenReturn(55L);

        new EpisodeSummarizer(gateway, gateway, episodes, conversations, committer, admission,
                12, new com.tutor.llm.structured.StructuredOutputService(gateway, null), null,
                new com.tutor.conversation.memory.policy.MemoryImportanceGate(), false)
                .maybeSummarize(1L, 7L, "trace");

        verify(committer).commitReturningId(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
                any(), any(), any(), any(), anyLong());
    }
}
