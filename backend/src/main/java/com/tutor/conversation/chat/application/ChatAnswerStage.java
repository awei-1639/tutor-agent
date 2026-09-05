package com.tutor.conversation.chat.application;

import com.tutor.conversation.chat.application.ChatModels.RetrievedContext;
import com.tutor.conversation.chat.application.ChatModels.TurnContext;
import com.tutor.conversation.chat.application.ChatModels.TurnState;
import com.tutor.conversation.chat.support.TraceRecorder;
import com.tutor.conversation.context.ContextPlanner;
import com.tutor.conversation.context.ConversationContextSelector;
import com.tutor.conversation.context.PromptAssembler;
import com.tutor.conversation.context.TokenBudget;
import com.tutor.conversation.context.TurnContextView;
import com.tutor.conversation.context.sections.EpisodeSection;
import com.tutor.conversation.context.sections.FactsSection;
import com.tutor.conversation.context.sections.ProfileSection;
import com.tutor.contract.CancellationToken;
import com.tutor.contract.ExpertOutput;
import com.tutor.contract.Intent;
import com.tutor.contract.Purpose;
import com.tutor.agent.expert.Aggregator;
import com.tutor.agent.expert.ExpertRunner;
import com.tutor.platform.llm.BudgetPressureService;
import com.tutor.platform.llm.LlmMessage;
import com.tutor.platform.llm.LlmStreamHandler;
import com.tutor.platform.llm.StreamingGenerationGateway;
import com.tutor.conversation.memory.local.ConversationStore;
import com.tutor.identity.resume.ResumeService;
import com.tutor.agent.tool.ToolCallLoop;
import com.tutor.agent.tool.ToolExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Direct-answer and expert-answer execution stage. Terminal persistence is delegated to the finalizer. */
final class ChatAnswerStage {
    private static final Logger log = LoggerFactory.getLogger(ChatAnswerStage.class);
    private static final int HISTORY_TURNS = 6;
    private static final int RESUME_BRIEFING_CHARS = 900;

    private final PromptAssembler promptAssembler;
    private final ProfileSection profileSection;
    private final TokenBudget tokenBudget;
    private final StreamingGenerationGateway gateway;
    private final ConversationStore conversations;
    private final ExpertRunner expertRunner;
    private final Aggregator aggregator;
    private final TraceRecorder trace;
    private final ResumeService resumeService;
    private final EpisodeSection episodeSection;
    private final FactsSection factsSection;
    private final ToolCallLoop toolCallLoop;
    private final ChatCompletionFinalizer completionFinalizer;
    private final boolean toolLoopEnabled;
    private volatile BudgetPressureService budgetPressure;

    ChatAnswerStage(PromptAssembler promptAssembler, ProfileSection profileSection, TokenBudget tokenBudget,
                    StreamingGenerationGateway gateway, ConversationStore conversations,
                    ExpertRunner expertRunner, Aggregator aggregator, TraceRecorder trace,
                    ResumeService resumeService, EpisodeSection episodeSection, FactsSection factsSection,
                    ToolCallLoop toolCallLoop, ChatCompletionFinalizer completionFinalizer,
                    boolean toolLoopEnabled) {
        this.promptAssembler = promptAssembler;
        this.profileSection = profileSection;
        this.tokenBudget = tokenBudget;
        this.gateway = gateway;
        this.conversations = conversations;
        this.expertRunner = expertRunner;
        this.aggregator = aggregator;
        this.trace = trace;
        this.resumeService = resumeService;
        this.episodeSection = episodeSection;
        this.factsSection = factsSection;
        this.toolCallLoop = toolCallLoop;
        this.completionFinalizer = completionFinalizer;
        this.toolLoopEnabled = toolLoopEnabled;
    }

    void setBudgetPressure(BudgetPressureService budgetPressure) {
        this.budgetPressure = budgetPressure;
    }

    void dispatch(TurnState state, String traceId, ChatTurnEvents events,
                  CancellationToken cancellation, ChatTurnService.Claim claim) {
        String question = state.originalQuestion();
        String executionQuestion = state.executionQuestion();
        TurnContext context = state.context();
        var plan = state.routed().plan();
        RetrievedContext retrieved = state.retrieved();
        List<String> expertNames = ExpertRunner.expertsFor(plan.intents());
        BudgetPressureService pressure = budgetPressure;
        if (expertNames.size() > 1 && pressure != null) {
            int cap = pressure.maxExperts();
            if (expertNames.size() > cap) {
                log.info("expert fan-out capped by budget pressure {} -> {} trace={}",
                        expertNames.size(), cap, traceId);
                expertNames = expertNames.subList(0, cap);
            }
        }
        if (expertNames.isEmpty()) {
            List<ConversationStore.Msg> history = ConversationContextSelector.select(
                    context.recentWindow(), executionQuestion, HISTORY_TURNS * 2);
            directStream(context, question, retrieved, history, plan.intent(), traceId,
                    events, cancellation, claim);
            return;
        }
        runExpertsAndAggregate(expertNames, context, question, executionQuestion, retrieved, plan.intent(),
                traceId, events, cancellation, claim);
    }

    private void runExpertsAndAggregate(List<String> expertNames, TurnContext context, String question,
                                        String executionQuestion, RetrievedContext retrieved, Intent intent,
                                        String traceId, ChatTurnEvents events, CancellationToken cancellation,
                                        ChatTurnService.Claim claim) {
        long convId = context.convId();
        String contextText = renderSharedContext(context.profile(), retrieved.episodes(), retrieved.facts());
        ExpertRunner.Briefing briefing = buildBriefing(context, retrieved, executionQuestion, contextText, traceId);

        for (String name : expertNames) events.onStage("expert:" + name);
        long expertStart = System.currentTimeMillis();
        List<ExpertOutput> outputs = expertRunner.run(expertNames, briefing.text(), traceId,
                stage -> {
                    if (!cancellation.isCancelled()) {
                        events.onExpertDone(stage.expert(), stage.status(), stage.detail());
                    }
                }, cancellation, briefing.citationIds());
        trace.span(traceId, convId, "experts", expertStart, outputs.size() < expertNames.size());
        if (cancellation.isCancelled()) return;

        events.onStage("aggregating");
        long aggregateStart = System.currentTimeMillis();
        aggregator.aggregateStream(outputs, executionQuestion, contextText, traceId,
                new Aggregator.AggregateEvents() {
                    @Override public void onToken(String token) {
                        if (!cancellation.isCancelled()) events.onToken(token);
                    }

                    @Override public void onClarify(String clarification) {
                        if (!cancellation.isCancelled()) events.onClarify(clarification);
                    }

                    @Override public void onComplete(String fullText, boolean clarified) {
                        onComplete(fullText, clarified, false);
                    }

                    @Override public void onComplete(String fullText, boolean clarified, boolean truncated) {
                        trace.span(traceId, convId, "aggregate", aggregateStart, clarified);
                        completionFinalizer.completeAnswer(fullText,
                                clarified ? "clarify" : intent.name().toLowerCase(), context, question,
                                retrieved.evidences(), briefing.citationIds(), traceId, events,
                                cancellation, claim, truncated);
                    }

                    @Override public void onError(Throwable error) {
                        log.error("aggregate error trace={}", traceId, error);
                        if (!cancellation.isCancelled()) events.onError("生成失败, 请稍后重试");
                    }
                }, cancellation);
    }

    private String renderSharedContext(Map<String, Object> profile,
                                       List<com.tutor.conversation.memory.local.EpisodeStore.Episode> episodes,
                                       List<com.tutor.conversation.memory.local.FactStore.UserFact> facts) {
        return profileSection.render(new TurnContextView(profile, List.of()), tokenBudget)
                + factsSection.render(new TurnContextView(profile, List.of(), null, List.of(), facts), tokenBudget)
                + episodeSection.render(new TurnContextView(profile, List.of(), null, episodes), tokenBudget);
    }

    private ExpertRunner.Briefing buildBriefing(TurnContext context, RetrievedContext retrieved,
                                                String executionQuestion, String contextText, String traceId) {
        String resumeText = resumeService.latestStructuredCompact(context.userId(), RESUME_BRIEFING_CHARS);
        long start = System.currentTimeMillis();
        ExpertRunner.Briefing briefing = expertRunner.buildBriefing(
                contextText + '\n' + resumeText, retrieved.evidences(), executionQuestion);
        trace.span(traceId, context.convId(), "expert_context", start, false, Map.of(
                "profile_original_tokens", briefing.usage().profileOriginalTokens(),
                "profile_allocated_tokens", briefing.usage().profileAllocatedTokens(),
                "evidence_original_tokens", briefing.usage().evidenceOriginalTokens(),
                "evidence_allocated_tokens", briefing.usage().evidenceAllocatedTokens(),
                "question_tokens", briefing.usage().questionTokens(),
                "total_budget", briefing.usage().totalBudget(),
                "truncated", briefing.usage().truncated()));
        return briefing;
    }

    private void directStream(TurnContext context, String question, RetrievedContext retrieved,
                              List<ConversationStore.Msg> history, Intent intent, String traceId,
                              ChatTurnEvents events, CancellationToken cancellation,
                              ChatTurnService.Claim claim) {
        PromptAssembler.Assembled assembled = assembleDirectPrompt(context, retrieved, traceId);
        List<LlmMessage> messages = directMessages(assembled, history, question);
        String intentName = intent.name().toLowerCase();
        if (toolLoopEnabled && toolCallLoop != null
                && streamViaToolLoop(context, question, retrieved, messages, assembled, intentName, traceId,
                events, cancellation, claim)) return;

        StringBuilder full = new StringBuilder();
        gateway.chatStream(Purpose.CHAT, messages, traceId, new LlmStreamHandler() {
            @Override public void onToken(String token) {
                full.append(token);
                if (!cancellation.isCancelled()) events.onToken(token);
            }

            @Override public void onComplete(com.tutor.platform.llm.LlmStreamResult response) {
                completionFinalizer.completeAnswer(full.toString(), intentName, context, question,
                        retrieved.evidences(), assembled.citationIds(), traceId, events, cancellation,
                        claim, response.truncated());
            }

            @Override public void onError(Throwable error) {
                log.error("direct stream error trace={}", traceId, error);
                if (!cancellation.isCancelled()) events.onError("生成失败, 请稍后重试");
            }
        }, cancellation);
    }

    private boolean streamViaToolLoop(TurnContext context, String question, RetrievedContext retrieved,
                                      List<LlmMessage> messages, PromptAssembler.Assembled assembled,
                                      String intentName, String traceId, ChatTurnEvents events,
                                      CancellationToken cancellation, ChatTurnService.Claim claim) {
        try {
            ToolCallLoop.LoopResult loopResult = toolCallLoop.run(Purpose.CHAT, messages, traceId,
                    new ToolExecutionContext(traceId, "chat", context.userId(), null, false));
            completionFinalizer.completeAnswer(loopResult.answer(), intentName, context, question,
                    retrieved.evidences(), assembled.citationIds(), traceId, events, cancellation, claim, false);
            return true;
        } catch (RuntimeException error) {
            log.warn("tool loop failed, falling back to streaming direct answer trace={} type={}",
                    traceId, error.getClass().getSimpleName());
            return false;
        }
    }

    private PromptAssembler.Assembled assembleDirectPrompt(TurnContext context, RetrievedContext retrieved,
                                                           String traceId) {
        String summary = conversations.summaryState(context.convId()).summary();
        long start = System.currentTimeMillis();
        PromptAssembler.Assembled assembled = promptAssembler.assembleWithMetadata(
                new TurnContextView(context.profile(), retrieved.evidences(), summary,
                        retrieved.episodes(), retrieved.facts()), traceId);
        trace.span(traceId, context.convId(), "context", start, false, Map.of(
                "total_original_tokens", assembled.allocations().stream()
                        .mapToInt(ContextPlanner.Allocation::originalTokens).sum(),
                "total_allocated_tokens", assembled.allocations().stream()
                        .mapToInt(ContextPlanner.Allocation::allocatedTokens).sum(),
                "sections", assembled.allocations().stream().map(allocation -> Map.of(
                        "name", allocation.name(), "original_tokens", allocation.originalTokens(),
                        "allocated_tokens", allocation.allocatedTokens(),
                        "dropped", allocation.dropped())).toList()));
        return assembled;
    }

    private List<LlmMessage> directMessages(PromptAssembler.Assembled assembled,
                                             List<ConversationStore.Msg> history, String question) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(assembled.prompt()));
        for (ConversationStore.Msg message : history) {
            messages.add("user".equals(message.role)
                    ? LlmMessage.user(message.content) : LlmMessage.assistant(message.content));
        }
        messages.add(LlmMessage.user(question));
        return messages;
    }
}
