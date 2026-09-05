package com.tutor.conversation.chat.application;

import com.tutor.identity.auth.AuthContext;
import com.tutor.conversation.chat.application.ChatService;
import com.tutor.conversation.chat.support.TraceRecorder;
import com.tutor.conversation.context.PromptAssembler;
import com.tutor.conversation.context.TokenBudget;
import com.tutor.conversation.context.sections.ProfileSection;
import com.tutor.contract.Intent;
import com.tutor.contract.CancellationToken;
import com.tutor.expert.Aggregator;
import com.tutor.expert.ExpertRunner;
import com.tutor.expert.IntentRouter;
import com.tutor.guard.CitationGuard;
import com.tutor.llm.LlmGateway;
import com.tutor.conversation.memory.application.LongTermMemoryService;
import com.tutor.conversation.memory.local.ConversationStore;
import com.tutor.conversation.memory.local.EpisodeSummarizer;
import com.tutor.conversation.memory.local.SummaryFolder;
import com.tutor.conversation.memory.policy.MemoryConsentService;
import com.tutor.identity.profile.ProfileService;
import com.tutor.knowledge.retrieval.agentic.AgenticRetriever;
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
        com.tutor.identity.resume.ResumeService resumes = mock(com.tutor.identity.resume.ResumeService.class);
        SummaryFolder summaryFolder = mock(SummaryFolder.class);
        EpisodeSummarizer episodeSummarizer = mock(EpisodeSummarizer.class);
        LongTermMemoryService longTermMemory = mock(LongTermMemoryService.class);
        com.tutor.conversation.context.sections.EpisodeSection episodeSection = mock(com.tutor.conversation.context.sections.EpisodeSection.class);
        CitationGuard citationGuard = mock(CitationGuard.class);
        com.tutor.expert.RoutingPolicy routingPolicy = new com.tutor.expert.RoutingPolicy();
        MemoryConsentService memoryConsent = mock(MemoryConsentService.class);
        CitationVerificationService citationVerification = new CitationVerificationService(conversations, citationGuard);
        PostTurnTaskService postTurnTasks = new PostTurnTaskService(profiles, summaryFolder, episodeSummarizer,
                longTermMemory, memoryConsent);
        ChatService service = new ChatService(agenticRetriever, promptAssembler, profileSection, tokenBudget,
                gateway, conversations, profiles, router, routingPolicy, expertRunner, aggregator, trace, resumes,
                longTermMemory, episodeSection,
                mock(com.tutor.conversation.memory.application.FactRecallService.class),
                mock(com.tutor.conversation.context.sections.FactsSection.class),
                citationVerification, postTurnTasks, memoryConsent);

        AuthContext.set(42L);
        when(conversations.ensureConversation(isNull(), eq(42L))).thenReturn(9L);
        when(conversations.recentMessages(9L, 24)).thenReturn(List.of());
        when(conversations.summaryState(9L)).thenReturn(new ConversationStore.SummaryState(null, 0));
        when(profiles.snapshot(42L)).thenReturn(Map.of());
        when(router.routeDecision(anyString(), any(), anyString())).thenReturn(
                new IntentRouter.RouteDecision(IntentRouter.Scope.OUT_OF_SCOPE, Intent.OUT_OF_SCOPE, List.of(), List.of(),
                        IntentRouter.RetrievalHint.NONE, 1D, 1D, List.of("test"), false));
        when(promptAssembler.assembleWithMetadata(any(), anyString()))
                .thenReturn(new PromptAssembler.Assembled("system", Set.of()));
        when(citationGuard.guard(anyString(), any(), anyString()))
                .thenReturn(new CitationGuard.GuardResult(0, 0, List.of(), 1.0, "not_applicable"));
        doAnswer(invocation -> {
            invocation.<com.tutor.llm.LlmStreamHandler>getArgument(3)
                    .onComplete(new com.tutor.llm.LlmStreamResult("test", 0, 0, false));
            return null;
        }).when(gateway).chatStream(any(), any(), anyString(), any(), any(CancellationToken.class));

        service.turn(null, "hello", new NoopTurnEvents());

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(profiles).updateFromMessage(eq(42L), eq("hello"), anyString(), anyLong());
            verify(episodeSummarizer).maybeSummarize(eq(9L), eq(42L), anyString(), anyLong());
        });
    }

    private static class NoopTurnEvents implements ChatTurnEvents {
        @Override public void onMeta(long conversationId, String traceId) { }
        @Override public void onStage(String phase) { }
        @Override public void onCitations(List<com.tutor.contract.Evidence> evidences) { }
        @Override public void onToken(String token) { }
        @Override public void onClarify(String question) { }
        @Override public void onDone(long messageId, String fullText) { }
        @Override public void onError(String message) { }
    }
}
