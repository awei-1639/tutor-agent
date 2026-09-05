package com.tutor.conversation.chat.application;

import com.tutor.conversation.chat.support.TraceRecorder;
import com.tutor.conversation.context.PromptAssembler;
import com.tutor.conversation.context.TokenBudget;
import com.tutor.conversation.context.sections.EpisodeSection;
import com.tutor.conversation.context.sections.ProfileSection;
import com.tutor.contract.CancellationToken;
import com.tutor.contract.Evidence;
import com.tutor.contract.Intent;
import com.tutor.expert.Aggregator;
import com.tutor.expert.ExpertRunner;
import com.tutor.expert.IntentRouter;
import com.tutor.expert.RoutingPolicy;
import com.tutor.guard.CitationGuard;
import com.tutor.llm.LlmGateway;
import com.tutor.conversation.memory.application.LongTermMemoryService;
import com.tutor.conversation.memory.local.ConversationStore;
import com.tutor.conversation.memory.local.EpisodeSummarizer;
import com.tutor.conversation.memory.local.SummaryFolder;
import com.tutor.conversation.memory.policy.MemoryConsentService;
import com.tutor.identity.profile.ProfileService;
import com.tutor.identity.resume.ResumeService;
import com.tutor.knowledge.retrieval.agentic.AgenticRetriever;
import com.tutor.knowledge.retrieval.fusion.FusedRetriever;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatService 有 25 个协作者，逐个用例重复搭建会掩盖每个测试真正关心的差异。
 * 这个 fixture 提供一条可用的默认直答链路，用例只覆写自己要验证的那一环。
 */
final class ChatServiceFixture {
    static final long USER_ID = 42L;
    static final long CONVERSATION_ID = 9L;

    final AgenticRetriever agenticRetriever = mock(AgenticRetriever.class);
    final PromptAssembler promptAssembler = mock(PromptAssembler.class);
    final ProfileSection profileSection = mock(ProfileSection.class);
    final TokenBudget tokenBudget = mock(TokenBudget.class);
    final LlmGateway gateway = mock(LlmGateway.class);
    final ConversationStore conversations = mock(ConversationStore.class);
    final ProfileService profiles = mock(ProfileService.class);
    final IntentRouter router = mock(IntentRouter.class);
    final RoutingPolicy routingPolicy = new RoutingPolicy();
    final ExpertRunner expertRunner = mock(ExpertRunner.class);
    final Aggregator aggregator = mock(Aggregator.class);
    final TraceRecorder trace = mock(TraceRecorder.class);
    final ResumeService resumes = mock(ResumeService.class);
    final SummaryFolder summaryFolder = mock(SummaryFolder.class);
    final EpisodeSummarizer episodeSummarizer = mock(EpisodeSummarizer.class);
    final LongTermMemoryService longTermMemory = mock(LongTermMemoryService.class);
    final EpisodeSection episodeSection = mock(EpisodeSection.class);
    final com.tutor.conversation.memory.application.FactRecallService factRecall = mock(com.tutor.conversation.memory.application.FactRecallService.class);
    final com.tutor.conversation.context.sections.FactsSection factsSection = mock(com.tutor.conversation.context.sections.FactsSection.class);
    final CitationGuard citationGuard = mock(CitationGuard.class);
    final MemoryConsentService memoryConsent = mock(MemoryConsentService.class);
    final CitationVerificationService citationVerification;
    final PostTurnTaskService postTurnTasks;

    ChatServiceFixture() {
        citationVerification = new CitationVerificationService(conversations, citationGuard);
        postTurnTasks = new PostTurnTaskService(profiles, summaryFolder, episodeSummarizer,
                longTermMemory, memoryConsent);
        stubConversationDefaults();
        stubContextDefaults();
        stubRetrievalDefaults();
    }

    ChatService build() {
        return new ChatService(agenticRetriever, promptAssembler, profileSection, tokenBudget,
                gateway, conversations, profiles, router, routingPolicy, expertRunner, aggregator, trace, resumes,
                longTermMemory, episodeSection, factRecall, factsSection,
                citationVerification, postTurnTasks, memoryConsent);
    }

    /** 启用受控工具循环的构造器；toolLoopEnabled=false 时与 {@link #build()} 行为一致。 */
    ChatService buildWithToolLoop(com.tutor.tool.ToolCallLoop toolCallLoop, boolean toolLoopEnabled) {
        return new ChatService(agenticRetriever, promptAssembler, profileSection, tokenBudget,
                gateway, conversations, profiles, router, routingPolicy, expertRunner, aggregator, trace, resumes,
                longTermMemory, episodeSection, factRecall, factsSection,
                citationVerification, postTurnTasks, memoryConsent, null, null, toolCallLoop, toolLoopEnabled);
    }

    private void stubConversationDefaults() {
        when(conversations.ensureConversation(isNull(), eq(USER_ID))).thenReturn(CONVERSATION_ID);
        when(conversations.recentMessages(eq(CONVERSATION_ID), anyInt())).thenReturn(List.of());
        when(conversations.summaryState(CONVERSATION_ID))
                .thenReturn(new ConversationStore.SummaryState(null, 0));
        when(conversations.clarificationState(CONVERSATION_ID)).thenReturn(null);
        when(conversations.appendMessage(anyLong(), anyString(), anyString(), any(), any(), anyString(), anyInt(),
                any(), any())).thenReturn(101L);
        when(profiles.snapshot(USER_ID)).thenReturn(Map.of());
        when(memoryConsent.currentGeneration(USER_ID)).thenReturn(1L);
    }

    private void stubContextDefaults() {
        when(promptAssembler.assembleWithMetadata(any(), anyString()))
                .thenReturn(new PromptAssembler.Assembled("system", Set.of()));
        when(profileSection.render(any(), any())).thenReturn("profile");
        when(episodeSection.render(any(), any())).thenReturn("episodes");
        when(resumes.latestStructuredCompact(anyLong(), anyInt())).thenReturn("resume");
        when(citationGuard.guard(anyString(), any(), anyString()))
                .thenReturn(new CitationGuard.GuardResult(0, 0, List.of(), 1.0, "not_applicable"));
    }

    private void stubRetrievalDefaults() {
        when(longTermMemory.recall(anyLong(), anyString(), anyString()))
                .thenReturn(new LongTermMemoryService.RecallResult(List.of(), false));
        when(factRecall.recall(anyLong(), anyString(), anyString())).thenReturn(List.of());
        when(factsSection.render(any(), any())).thenReturn("");
        when(agenticRetriever.retrievalProfileVersion()).thenReturn("test-profile");
        stubRetrieval(List.of());
    }

    /** 默认路由：领域内 CHAT，单跳，无竞争意图 → 走直答路径，不扇出专家。 */
    void routeAsDirectChat() {
        when(router.routeDecision(anyString(), any(), anyString())).thenReturn(new IntentRouter.RouteDecision(
                IntentRouter.Scope.IN_SCOPE, Intent.CHAT, List.of(), List.of(),
                IntentRouter.RetrievalHint.SINGLE, 0.95D, 0.95D, List.of(), false));
    }

    /** 高置信度越界 → 跳过检索。 */
    void routeAsOutOfScope() {
        when(router.routeDecision(anyString(), any(), anyString())).thenReturn(new IntentRouter.RouteDecision(
                IntentRouter.Scope.OUT_OF_SCOPE, Intent.OUT_OF_SCOPE, List.of(), List.of(),
                IntentRouter.RetrievalHint.NONE, 1D, 1D, List.of("test"), false));
    }

    /** 领域内 RESUME → 触发专家扇出与仲裁。 */
    void routeAsResumeExpert() {
        when(router.routeDecision(anyString(), any(), anyString())).thenReturn(new IntentRouter.RouteDecision(
                IntentRouter.Scope.IN_SCOPE, Intent.RESUME, List.of(Intent.RESUME), List.of(),
                IntentRouter.RetrievalHint.SINGLE, 0.95D, 0.95D, List.of(), false));
    }

    void stubRetrieval(List<Evidence> evidences) {
        when(agenticRetriever.retrieveAdaptiveResult(anyString(), anyInt(), anyString(), anyBoolean(), any(), any()))
                .thenReturn(new AgenticRetriever.RetrievalResult(evidences, 1, false, "sufficient",
                        FusedRetriever.RetrievalTelemetry.empty()));
    }

    /** 让专家扇出返回给定输出，并让仲裁把 answer 作为一次完整流式回答吐出。 */
    void stubExpertFanOut(List<com.tutor.contract.ExpertOutput> outputs, String answer) {
        when(expertRunner.buildBriefing(anyString(), any(), anyString()))
                .thenReturn(new ExpertRunner.Briefing("briefing", Set.of()));
        when(expertRunner.run(any(), anyString(), anyString(), any(), any(), any())).thenReturn(outputs);
        org.mockito.Mockito.doAnswer(invocation -> {
            Aggregator.AggregateEvents events = invocation.getArgument(4);
            events.onToken(answer);
            events.onComplete(answer, false);
            return null;
        }).when(aggregator).aggregateStream(any(), anyString(), anyString(), anyString(), any(), any());
    }
}



