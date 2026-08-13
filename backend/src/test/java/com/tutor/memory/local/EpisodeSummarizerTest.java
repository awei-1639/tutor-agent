package com.tutor.memory.local;

import com.tutor.llm.LlmGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class EpisodeSummarizerTest {
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
                new ConversationStore.Msg(13, "user", "c"), new ConversationStore.Msg(14, "assistant", "d")));
        when(gateway.chatJson(any(), any(), any())).thenReturn("{\"summary\":\"summary\",\"topics\":[],\"open_items\":[]}");
        when(gateway.embed(any(), any())).thenReturn(new float[]{0.1f});
        when(admission.acceptsEpisode(any(), any(), any())).thenReturn(true);
        when(committer.commit(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), any(), any())).thenReturn(true);

        new EpisodeSummarizer(gateway, episodes, conversations, committer, admission).maybeSummarize(1L, 7L, "trace");

        verify(committer).commit(eq(7L), eq(1L), eq(10L), eq(11L), eq(14L), eq("summary"), any(), any(), any());
        verify(episodes, never()).insert(anyLong(), any(), any(), any(), any(), any());
    }
}
