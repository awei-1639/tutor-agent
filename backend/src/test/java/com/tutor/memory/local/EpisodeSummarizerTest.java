package com.tutor.memory.local;

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
        com.tutor.memory.policy.MemoryAdmissionPolicy admission = mock(com.tutor.memory.policy.MemoryAdmissionPolicy.class);
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
        com.tutor.memory.policy.MemoryAdmissionPolicy admission = mock(com.tutor.memory.policy.MemoryAdmissionPolicy.class);
        when(conversations.episodeUptoMsgId(1L)).thenReturn(10L);
        when(conversations.messagesAfter(1L, 10L, 30)).thenReturn(List.of(
                new ConversationStore.Msg(11, "user", "a"), new ConversationStore.Msg(12, "assistant", "b"),
                new ConversationStore.Msg(13, "user", "c"), new ConversationStore.Msg(14, "assistant", "d"),
                new ConversationStore.Msg(15, "user", "e"), new ConversationStore.Msg(16, "assistant", "f"),
                new ConversationStore.Msg(17, "user", "g"), new ConversationStore.Msg(18, "assistant", "h"),
                new ConversationStore.Msg(19, "user", "i"), new ConversationStore.Msg(20, "assistant", "j"),
                new ConversationStore.Msg(21, "user", "k"), new ConversationStore.Msg(22, "assistant", "l")));
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
        com.tutor.memory.policy.MemoryAdmissionPolicy admission = mock(com.tutor.memory.policy.MemoryAdmissionPolicy.class);
        FactExtractionService factExtraction = mock(FactExtractionService.class);
        when(conversations.episodeUptoMsgId(1L)).thenReturn(10L);
        when(conversations.messagesAfter(1L, 10L, 30)).thenReturn(List.of(
                new ConversationStore.Msg(11, "user", "a"), new ConversationStore.Msg(12, "assistant", "b"),
                new ConversationStore.Msg(13, "user", "c"), new ConversationStore.Msg(14, "assistant", "d"),
                new ConversationStore.Msg(15, "user", "e"), new ConversationStore.Msg(16, "assistant", "f"),
                new ConversationStore.Msg(17, "user", "g"), new ConversationStore.Msg(18, "assistant", "h"),
                new ConversationStore.Msg(19, "user", "i"), new ConversationStore.Msg(20, "assistant", "j"),
                new ConversationStore.Msg(21, "user", "k"), new ConversationStore.Msg(22, "assistant", "l")));
        when(gateway.chatJson(any(), any(), any(), isNull(), eq(1)))
                .thenReturn("{\"summary\":\"summary\",\"topics\":[\"topic\"],\"open_items\":[]}");
        when(gateway.embed(any(), any())).thenReturn(new float[]{0.1f});
        when(admission.acceptsEpisode(any(), any(), any())).thenReturn(true);
        when(committer.commitReturningId(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
                any(), any(), any(), any(), anyLong())).thenReturn(55L);

        new EpisodeSummarizer(gateway, gateway, episodes, conversations, committer, admission,
                12, new com.tutor.llm.structured.StructuredOutputService(gateway, null), factExtraction)
                .maybeSummarize(1L, 7L, "trace");

        verify(factExtraction).extractFromWindow(eq(7L), eq(55L), eq(Long.MIN_VALUE), any(), eq("trace"));

        when(committer.commitReturningId(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
                any(), any(), any(), any(), anyLong())).thenReturn(0L);
        new EpisodeSummarizer(gateway, gateway, episodes, conversations, committer, admission,
                12, new com.tutor.llm.structured.StructuredOutputService(gateway, null), factExtraction)
                .maybeSummarize(1L, 7L, "trace2");
        verify(factExtraction, times(1)).extractFromWindow(anyLong(), anyLong(), anyLong(), any(), any());
    }
}
