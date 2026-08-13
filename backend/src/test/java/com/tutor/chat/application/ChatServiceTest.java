package com.tutor.chat.application;

import com.tutor.auth.AuthContext;
import com.tutor.chat.application.ChatService;
import com.tutor.chat.support.TraceRecorder;
import com.tutor.context.PromptAssembler;
import com.tutor.context.TokenBudget;
import com.tutor.context.sections.ProfileSection;
import com.tutor.contract.Intent;
import com.tutor.contract.CancellationToken;
import com.tutor.expert.Aggregator;
import com.tutor.expert.ExpertRunner;
import com.tutor.expert.IntentRouter;
import com.tutor.guard.CitationGuard;
import com.tutor.llm.LlmGateway;
import com.tutor.memory.application.LongTermMemoryService;
import com.tutor.memory.local.ConversationStore;
import com.tutor.memory.local.EpisodeSummarizer;
import com.tutor.memory.local.SummaryFolder;
import com.tutor.profile.ProfileService;
import com.tutor.retrieval.agentic.AgenticRetriever;
import com.tutor.retrieval.fusion.FusedRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceTest {
    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void keepsRequestUserForBackgroundWorkAfterStreamingCompletion() {
        FusedRetriever retriever = mock(FusedRetriever.class);
        AgenticRetriever agenticRetriever = mock(AgenticRetriever.class);
        PromptAssembler promptAssembler = mock(PromptAssembler.class);
        ProfileSection profileSection = mock(ProfileSection.class);
        TokenBudget tokenBudget = mock(TokenBudget.class);
        LlmGateway gateway = mock(LlmGateway.class);
        ConversationStore conversations = mock(ConversationStore.class);
        ProfileService profiles = mock(ProfileService.class);
        IntentRouter router = mock(IntentRouter.class);
        ExpertRunner expertRunner = mock(ExpertRunner.class);
        Aggregator aggregator = mock(Aggregator.class);
        TraceRecorder trace = mock(TraceRecorder.class);
        com.tutor.resume.ResumeService resumes = mock(com.tutor.resume.ResumeService.class);
        SummaryFolder summaryFolder = mock(SummaryFolder.class);
        EpisodeSummarizer episodeSummarizer = mock(EpisodeSummarizer.class);
        LongTermMemoryService longTermMemory = mock(LongTermMemoryService.class);
        com.tutor.context.sections.EpisodeSection episodeSection = mock(com.tutor.context.sections.EpisodeSection.class);
        CitationGuard citationGuard = mock(CitationGuard.class);
        ChatService service = new ChatService(retriever, agenticRetriever, promptAssembler, profileSection, tokenBudget,
                gateway, conversations, profiles, router, expertRunner, aggregator, trace, resumes,
                summaryFolder, episodeSummarizer, longTermMemory, episodeSection, citationGuard);

        AuthContext.set(42L);
        when(conversations.ensureConversation(isNull(), eq(42L))).thenReturn(9L);
        when(conversations.recentMessages(9L, 12)).thenReturn(List.of());
        when(conversations.summaryState(9L)).thenReturn(new ConversationStore.SummaryState(null, 0));
        when(profiles.snapshot(42L)).thenReturn(Map.of());
        when(router.route(anyString(), any(), anyString())).thenReturn(Intent.OUT_OF_SCOPE);
        when(promptAssembler.assembleWithMetadata(any(), anyString()))
                .thenReturn(new PromptAssembler.Assembled("system", Set.of()));
        when(citationGuard.guard(anyString(), any(), anyString()))
                .thenReturn(new CitationGuard.GuardResult(0, 0, List.of(), 1.0, "not_applicable"));
        doAnswer(invocation -> {
            invocation.<dev.langchain4j.model.chat.response.StreamingChatResponseHandler>getArgument(3)
                    .onCompleteResponse(null);
            return null;
        }).when(gateway).chatStream(any(), any(), anyString(), any(), any(CancellationToken.class));

        service.turn(null, "hello", new NoopTurnEvents());

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(profiles).updateFromMessage(eq(42L), eq("hello"), anyString());
            verify(episodeSummarizer).maybeSummarize(eq(9L), eq(42L), anyString());
        });
    }

    private static class NoopTurnEvents implements ChatService.TurnEvents {
        @Override public void onMeta(long conversationId, String traceId) { }
        @Override public void onStage(String phase) { }
        @Override public void onCitations(List<com.tutor.contract.Evidence> evidences) { }
        @Override public void onToken(String token) { }
        @Override public void onClarify(String question) { }
        @Override public void onDone(long messageId, String fullText) { }
        @Override public void onError(String message) { }
    }
}
