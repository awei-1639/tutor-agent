package com.tutor.chat.application;

import com.tutor.context.PromptAssembler;
import com.tutor.context.CoreferenceResolver;
import com.tutor.context.ContextualQueryRewriter;
import com.tutor.context.TokenBudget;
import com.tutor.chat.application.ChatModels.TurnState;
import com.tutor.context.sections.ProfileSection;
import com.tutor.chat.support.TraceRecorder;
import com.tutor.contract.Evidence;
import com.tutor.contract.CancellationToken;
import com.tutor.tool.ToolCallLoop;
import com.tutor.expert.Aggregator;
import com.tutor.expert.ExpertRunner;
import com.tutor.expert.IntentRouter;
import com.tutor.llm.BudgetExhausted;
import com.tutor.llm.BudgetPressureService;
import com.tutor.llm.LlmBudgetGuard;
import com.tutor.llm.LlmBusyException;
import com.tutor.llm.StreamingGenerationGateway;
import com.tutor.llm.structured.StructuredOutputService;
import com.tutor.memory.application.FactRecallService;
import com.tutor.memory.application.LongTermMemoryService;
import com.tutor.memory.local.ConversationStore;
import com.tutor.memory.policy.MemoryConsentService;
import com.tutor.profile.ProfileService;
import com.tutor.retrieval.agentic.AgenticRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PreDestroy;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 决策流编排 (V3 3.2): profile → router → {direct | experts→aggregate} → 落库 → 异步画像更新。
 * 等价 LangGraph 的图执行, 节点耗时入 turn_traces。
 */
@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private final com.tutor.expert.RoutingPolicy routingPolicy;
    private final ChatRoutingStage routingStage;
    private final ChatContextLoader contextLoader;
    private final ChatRetrievalStage retrievalStage;
    private final ChatCompletionFinalizer completionFinalizer;
    private final ChatAnswerStage answerStage;
    private volatile BudgetPressureService budgetPressure;
    public ChatService(AgenticRetriever agenticRetriever, PromptAssembler promptAssembler,
                       ProfileSection profileSection, TokenBudget tokenBudget,
                       StreamingGenerationGateway gateway, ConversationStore conversations,
                       ProfileService profileService, IntentRouter router,
                       com.tutor.expert.RoutingPolicy routingPolicy,
                       ExpertRunner expertRunner, Aggregator aggregator, TraceRecorder trace,
                       com.tutor.resume.ResumeService resumeService,
                       LongTermMemoryService longTermMemory,
                       com.tutor.context.sections.EpisodeSection episodeSection,
                       FactRecallService factRecall,
                       com.tutor.context.sections.FactsSection factsSection,
                       CitationVerificationService citationVerification,
                       PostTurnTaskService postTurnTasks,
                       MemoryConsentService memoryConsent) {
        this(agenticRetriever, promptAssembler, profileSection, tokenBudget, gateway, conversations,
                profileService, router, routingPolicy, expertRunner, aggregator, trace, resumeService,
                longTermMemory, episodeSection, factRecall, factsSection,
                citationVerification, postTurnTasks, memoryConsent,
                null, null, null, false);
    }

    @Autowired
    public ChatService(AgenticRetriever agenticRetriever, PromptAssembler promptAssembler,
                       ProfileSection profileSection, TokenBudget tokenBudget,
                       StreamingGenerationGateway gateway, ConversationStore conversations,
                       ProfileService profileService, IntentRouter router,
                       com.tutor.expert.RoutingPolicy routingPolicy,
                       ExpertRunner expertRunner, Aggregator aggregator, TraceRecorder trace,
                       com.tutor.resume.ResumeService resumeService,
                       LongTermMemoryService longTermMemory,
                       com.tutor.context.sections.EpisodeSection episodeSection,
                       FactRecallService factRecall,
                       com.tutor.context.sections.FactsSection factsSection,
                       CitationVerificationService citationVerification,
                       PostTurnTaskService postTurnTasks,
                       MemoryConsentService memoryConsent,
                       ChatTurnService chatTurns,
                       StructuredOutputService structuredOutputService,
                       ToolCallLoop toolCallLoop,
                       @Value("${tutor.chat.tool-loop-enabled:false}") boolean toolLoopEnabled) {
        this.routingPolicy = routingPolicy;
        this.contextLoader = new ChatContextLoader(conversations, profileService, memoryConsent);
        this.retrievalStage = new ChatRetrievalStage(
                agenticRetriever, longTermMemory, factRecall, trace);
        this.completionFinalizer = new ChatCompletionFinalizer(
                conversations, citationVerification, postTurnTasks, chatTurns);
        this.answerStage = new ChatAnswerStage(
                promptAssembler, profileSection, tokenBudget, gateway, conversations,
                expertRunner, aggregator, trace, resumeService, episodeSection, factsSection,
                toolCallLoop, completionFinalizer, toolLoopEnabled);
        this.routingStage = new ChatRoutingStage(
                new ContextualQueryRewriter(new CoreferenceResolver(structuredOutputService == null
                        ? new StructuredOutputService(null, null)
                        : structuredOutputService)),
                router, routingPolicy, trace);
    }

    @PreDestroy
    void shutdownBackgroundExecutor() {
        completionFinalizer.shutdown();
    }

    /** 可选注入：预算归属/快速失败与压力降级；未注入时退化为原有行为 (便于测试构造)。 */
    @Autowired(required = false)
    void setBudgetGuard(LlmBudgetGuard budgetGuard) {
        contextLoader.setBudgetGuard(budgetGuard);
    }

    @Autowired(required = false)
    void setBudgetPressure(BudgetPressureService budgetPressure) {
        routingStage.setBudgetPressure(budgetPressure);
        answerStage.setBudgetPressure(budgetPressure);
    }

    public void turn(Long conversationId, String question, ChatTurnEvents events) {
        turn(conversationId, question, events, new CancellationToken());
    }

    public void turn(Long conversationId, String question, ChatTurnEvents events, CancellationToken cancellation) {
        turn(conversationId, question, events, cancellation, null);
    }

    /** Execute a durable turn. A null claim is retained for legacy callers and unit tests. */
    public void turn(Long conversationId, String question, ChatTurnEvents events, CancellationToken cancellation,
                     ChatTurnService.Claim claim) {
        Objects.requireNonNull(cancellation, "cancellation");
        if (cancellation.isCancelled()) {
            return;
        }
        String traceId = claim == null ? UUID.randomUUID().toString().replace("-", "").substring(0, 16) : claim.traceId();
        try {
            TurnState state = new TurnState(question, question,
                    contextLoader.load(conversationId, question, traceId, events, claim), null, null);

            ContextualQueryRewriter.RewriteResult rewritten = routingStage.rewrite(
                    state.originalQuestion(), state.context(), traceId, events);
            if (rewritten.needsClarification()) {
                String mention = rewritten.references().isEmpty()
                        ? "该表达"
                        : rewritten.references().getFirst().mention();
                completionFinalizer.completeClarification(state.context(), state.originalQuestion(),
                        "你提到的“" + mention + "”可能指多个对象，请说明具体指哪个。", List.of(), "reference",
                        traceId, events, cancellation, claim);
                return;
            }
            // 代词/短追问需要带上上一轮主题用于检索和专家简报；直答路径仍保留原问题与历史消息。
            state = state.withExecutionQuestion(rewritten.standaloneQuery());

            state = state.withRouting(routingStage.route(
                    state.executionQuestion(), state.context(), traceId, events));
            if (routingPolicy.shouldClarify(state.routed().decision())) {
                completionFinalizer.completeClarification(state.context(), state.originalQuestion(),
                        routingPolicy.clarificationQuestion(state.routed().decision()),
                        routingPolicy.clarificationOptions(state.routed().decision()),
                        state.routed().decision().intent().name().toLowerCase(),
                        traceId, events, cancellation, claim);
                return;
            }

            state = state.withRetrieved(retrievalStage.retrieve(
                    state.executionQuestion(), state.context(), state.routed().plan(), traceId, events));
            if (cancellation.isCancelled()) {
                return;
            }
            answerStage.dispatch(state, traceId, events, cancellation, claim);
        } catch (Exception e) {
            log.error("turn error trace={}", traceId, e);
            if (!cancellation.isCancelled()) {
                // 预算与繁忙是可预期的用户可见状态，携带稳定机器码与友好文案；
                // 其余一律收敛为通用失败，内部不变量消息不再透传给用户。
                if (e instanceof BudgetExhausted exhausted) {
                    events.onError(exhausted.code(), exhausted.userMessage());
                } else if (e instanceof LlmBusyException busy) {
                    events.onError("llm_busy", busy.getMessage());
                } else {
                    events.onError("TURN_FAILED", "服务异常, 请稍后重试");
                }
            }
        }
    }

}
