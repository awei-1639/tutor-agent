package com.tutor.chat.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.context.PromptAssembler;
import com.tutor.context.ContextPlanner;
import com.tutor.context.ConversationContextSelector;
import com.tutor.context.CoreferenceResolver;
import com.tutor.context.ContextualQueryRewriter;
import com.tutor.context.TokenBudget;
import com.tutor.context.TurnContextView;
import com.tutor.context.sections.ProfileSection;
import com.tutor.auth.AuthContext;
import com.tutor.chat.support.TraceRecorder;
import com.tutor.config.ExecutorLifecycle;
import com.tutor.contract.Evidence;
import com.tutor.contract.ExpertOutput;
import com.tutor.contract.Intent;
import com.tutor.contract.Purpose;
import com.tutor.contract.CancellationToken;
import com.tutor.tool.ToolCallLoop;
import com.tutor.tool.ToolExecutionContext;
import com.tutor.expert.Aggregator;
import com.tutor.expert.ExpertRunner;
import com.tutor.expert.IntentRouter;
import com.tutor.guard.CitationSourcePolicy;
import com.tutor.llm.StreamingGenerationGateway;
import com.tutor.llm.structured.StructuredOutputService;
import com.tutor.memory.application.LongTermMemoryService;
import com.tutor.memory.local.ConversationStore;
import com.tutor.memory.local.EpisodeStore;
import com.tutor.memory.local.EpisodeSummarizer;
import com.tutor.memory.local.SummaryFolder;
import com.tutor.memory.policy.MemoryConsentService;
import com.tutor.profile.ProfileService;
import com.tutor.retrieval.agentic.AgenticRetriever;
import com.tutor.retrieval.fusion.FusedRetriever;
import com.tutor.retrieval.GraphScope;
import com.tutor.retrieval.graph.GraphExpansionPolicy;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;

/**
 * 决策流编排 (V3 3.2): profile → router → {direct | experts→aggregate} → 落库 → 异步画像更新。
 * 等价 LangGraph 的图执行, 节点耗时入 turn_traces。
 */
@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final Pattern CITE = Pattern.compile("\\[S(\\d+)]");
    private static final int TOP_K = 5;
    private static final int HISTORY_TURNS = 6;
    private static long currentUserId() { return AuthContext.requireUserId(); }

    private final FusedRetriever retriever;
    private final AgenticRetriever agenticRetriever;
    private final PromptAssembler promptAssembler;
    private final ProfileSection profileSection;
    private final TokenBudget tokenBudget;
    private final StreamingGenerationGateway gateway;
    private final ConversationStore conversations;
    private final ProfileService profileService;
    private final IntentRouter router;
    private final com.tutor.expert.RoutingPolicy routingPolicy;
    private final ExpertRunner expertRunner;
    private final Aggregator aggregator;
    private final TraceRecorder trace;
    private final com.tutor.resume.ResumeService resumeService;
    private final SummaryFolder summaryFolder;
    private final EpisodeSummarizer episodeSummarizer;
    private final LongTermMemoryService longTermMemory;
    private final com.tutor.context.sections.EpisodeSection episodeSection;
    private final CitationVerificationService citationVerification;
    private final PostTurnTaskService postTurnTasks;
    private final MemoryConsentService memoryConsent;
    private final ChatTurnService chatTurns;
    private final ToolCallLoop toolCallLoop;
    private final boolean toolLoopEnabled;
    private final ContextualQueryRewriter queryRewriter;
    private final TurnCitations citations = new TurnCitations();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    private record CitationBundle(String json, String status, String issuesJson) {}

    public ChatService(FusedRetriever retriever, AgenticRetriever agenticRetriever, PromptAssembler promptAssembler,
                       ProfileSection profileSection, TokenBudget tokenBudget,
                       StreamingGenerationGateway gateway, ConversationStore conversations,
                       ProfileService profileService, IntentRouter router,
                       com.tutor.expert.RoutingPolicy routingPolicy,
                       ExpertRunner expertRunner, Aggregator aggregator, TraceRecorder trace,
                       com.tutor.resume.ResumeService resumeService,
                       SummaryFolder summaryFolder,
                       EpisodeSummarizer episodeSummarizer,
                       LongTermMemoryService longTermMemory,
                       com.tutor.context.sections.EpisodeSection episodeSection,
                       CitationVerificationService citationVerification,
                       PostTurnTaskService postTurnTasks,
                       MemoryConsentService memoryConsent) {
        this(retriever, agenticRetriever, promptAssembler, profileSection, tokenBudget, gateway, conversations,
                profileService, router, routingPolicy, expertRunner, aggregator, trace, resumeService, summaryFolder,
                episodeSummarizer, longTermMemory, episodeSection, citationVerification, postTurnTasks, memoryConsent,
                null, null, null, false);
    }

    @Autowired
    public ChatService(FusedRetriever retriever, AgenticRetriever agenticRetriever, PromptAssembler promptAssembler,
                       ProfileSection profileSection, TokenBudget tokenBudget,
                       StreamingGenerationGateway gateway, ConversationStore conversations,
                       ProfileService profileService, IntentRouter router,
                       com.tutor.expert.RoutingPolicy routingPolicy,
                       ExpertRunner expertRunner, Aggregator aggregator, TraceRecorder trace,
                       com.tutor.resume.ResumeService resumeService,
                       SummaryFolder summaryFolder,
                       EpisodeSummarizer episodeSummarizer,
                       LongTermMemoryService longTermMemory,
                       com.tutor.context.sections.EpisodeSection episodeSection,
                       CitationVerificationService citationVerification,
                       PostTurnTaskService postTurnTasks,
                       MemoryConsentService memoryConsent,
                       ChatTurnService chatTurns,
                       StructuredOutputService structuredOutputService,
                       ToolCallLoop toolCallLoop,
                       @Value("${tutor.chat.tool-loop-enabled:false}") boolean toolLoopEnabled) {
        this.retriever = retriever;
        this.agenticRetriever = agenticRetriever;
        this.promptAssembler = promptAssembler;
        this.profileSection = profileSection;
        this.tokenBudget = tokenBudget;
        this.gateway = gateway;
        this.conversations = conversations;
        this.profileService = profileService;
        this.router = router;
        this.routingPolicy = routingPolicy;
        this.expertRunner = expertRunner;
        this.aggregator = aggregator;
        this.trace = trace;
        this.resumeService = resumeService;
        this.summaryFolder = summaryFolder;
        this.episodeSummarizer = episodeSummarizer;
        this.longTermMemory = longTermMemory;
        this.episodeSection = episodeSection;
        this.citationVerification = citationVerification;
        this.postTurnTasks = postTurnTasks;
        this.memoryConsent = memoryConsent;
        this.chatTurns = chatTurns;
        this.toolCallLoop = toolCallLoop;
        this.toolLoopEnabled = toolLoopEnabled;
        this.queryRewriter = new ContextualQueryRewriter(
                new CoreferenceResolver(structuredOutputService == null
                        ? new StructuredOutputService(null, null)
                        : structuredOutputService));
    }

    @PreDestroy
    void shutdownBackgroundExecutor() {
        ExecutorLifecycle.shutdown(background, "chat-background", log);
    }

    public interface TurnEvents {
        void onMeta(long conversationId, String traceId);
        void onStage(String phase);
        default void onExpertDone(String expert, String status, String detail) {
            onStage("expert_done:" + expert + ":" + status);
        }
        void onCitations(List<Evidence> evidences);
        void onToken(String token);
        void onClarify(String question);
        default void onClarify(String question, List<Map<String, String>> options) {
            onClarify(question);
        }
        void onDone(long messageId, String fullText);
        default void onDone(long messageId, String fullText, String citationStatus, List<String> citationIssues) {
            onDone(messageId, fullText);
        }
        void onError(String message);
    }

    public void turn(Long conversationId, String question, TurnEvents events) {
        turn(conversationId, question, events, new CancellationToken());
    }

    public void turn(Long conversationId, String question, TurnEvents events, CancellationToken cancellation) {
        turn(conversationId, question, events, cancellation, null);
    }

    /** Execute a durable turn. A null claim is retained for legacy callers and unit tests. */
    public void turn(Long conversationId, String question, TurnEvents events, CancellationToken cancellation,
                     ChatTurnService.Claim claim) {
        Objects.requireNonNull(cancellation, "cancellation");
        if (cancellation.isCancelled()) {
            return;
        }
        String traceId = claim == null ? UUID.randomUUID().toString().replace("-", "").substring(0, 16) : claim.traceId();
        try {
            // LLM 回调和后台任务会切换线程；在请求线程中一次性固定身份，不能在回调中再读 ThreadLocal。
            long userId = claim == null ? currentUserId() : claim.userId();
            long memoryGeneration = memoryConsent.currentGeneration(userId);
            long convId = claim == null ? conversations.ensureConversation(conversationId, userId) : claim.conversationId();
            events.onMeta(convId, traceId);
            ConversationStore.ClarificationState clarificationState = conversations.clarificationState(convId);
            if (clarificationState == null) {
                clarificationState = new ConversationStore.ClarificationState(false, null, null);
            }
            // 路由只需要最小会话上下文；完整的相关性筛选延后到确定走直答路径后再做。
            List<ConversationStore.Msg> recentWindow = conversations.recentMessages(convId, HISTORY_TURNS * 4);
            if (claim == null) {
                conversations.appendMessage(convId, "user", question, null, null, traceId, question.length() / 2);
            } else if (!recentWindow.isEmpty()) {
                // Admission already persisted this user message. Exclude it
                // from history so recovery has the same prompt as first run.
                ConversationStore.Msg latest = recentWindow.getLast();
                if ("user".equals(latest.role) && question.equals(latest.content)) {
                    recentWindow = new ArrayList<>(recentWindow.subList(0, recentWindow.size() - 1));
                }
            }
            if (clarificationState.pending()) {
                conversations.clearClarification(convId);
            }
            Map<String, Object> profile = profileService.snapshot(userId);

            // 统一的用户查询改写：代词消解、短追问主题补全和失败模式都在此收口。
            events.onStage("rewriting");
            long rewriteStart = System.currentTimeMillis();
            ContextualQueryRewriter.RewriteResult rewritten =
                    queryRewriter.rewrite(question, recentWindow, traceId);
            String routingQuestion = rewritten.standaloneQuery();
            trace.span(traceId, convId, "query_rewrite", rewriteStart,
                    rewritten.needsClarification(), Map.of(
                            "mode", rewritten.mode().name().toLowerCase(),
                            "rewritten", !java.util.Objects.equals(question, routingQuestion),
                            "reference_count", rewritten.references().size(),
                            "needs_clarification", rewritten.needsClarification()));
            if (rewritten.needsClarification()) {
                String mention = rewritten.references().isEmpty()
                        ? "该表达"
                        : rewritten.references().getFirst().mention();
                String clarification = "你提到的“" + mention + "”可能指多个对象，请说明具体指哪个。";
                events.onStage("clarifying");
                events.onClarify(clarification);
                Long messageId = persistAssistant(convId, clarification, "clarify", null, traceId,
                        clarification.length() / 2, "unavailable", "[]", claim, cancellation);
                if (messageId != null) {
                    conversations.setClarificationPending(
                            convId, "reference", java.time.Instant.now().plus(Duration.ofMinutes(10)));
                    events.onDone(messageId, clarification, "unavailable", List.of());
                }
                if (messageId != null) {
                    background.submit(() -> postTurnTasks.run(
                            convId, userId, question, clarification, traceId, memoryGeneration));
                }
                return;
            }

            // --- router 节点 ---
            events.onStage("routing");
            long t0 = System.currentTimeMillis();
            List<String> recentUser = ConversationContextSelector.routerContext(recentWindow, routingQuestion);
            if (clarificationState.pending()) {
                recentUser = new ArrayList<>(recentUser);
                recentUser.add("系统提示：当前用户回复可能是在回答上一轮澄清问题，请优先结合该澄清上下文理解。");
            }
            IntentRouter.RouteDecision routeDecision = router.routeDecision(routingQuestion, recentUser, traceId);
            if (routeDecision == null) {
                throw new IllegalStateException("路由决策不能为空");
            }
            com.tutor.expert.RoutingPolicy.ExecutionPlan executionPlan = routingPolicy.plan(routeDecision, routingQuestion);
            Intent intent = executionPlan.intent();
            trace.span(traceId, convId, "router", t0, executionPlan.degraded(), Map.ofEntries(
                    Map.entry("scope", routeDecision.scope().name().toLowerCase()),
                    Map.entry("intent", routeDecision.intent().name().toLowerCase()),
                    Map.entry("sub_intents", routeDecision.subIntents().stream().map(Enum::name).map(String::toLowerCase).toList()),
                    Map.entry("effective_intent", executionPlan.intent().name().toLowerCase()),
                    Map.entry("confidence", routeDecision.confidence()),
                    Map.entry("alternative_confidence", routeDecision.alternativeConfidence() == null
                            ? "unavailable" : routeDecision.alternativeConfidence()),
                    Map.entry("calibrated_confidence", routeDecision.calibratedConfidence() == null
                            ? "uncalibrated" : routeDecision.calibratedConfidence()),
                    Map.entry("retrieval_hint", routeDecision.retrievalHint().name().toLowerCase()),
                    Map.entry("skip_retrieval", executionPlan.skipRetrieval()),
                    Map.entry("allow_multi_hop", executionPlan.allowMultiHopEscalation()),
                    Map.entry("retrieval_facets", executionPlan.retrievalFacets().stream()
                            .map(Enum::name).map(String::toLowerCase).toList()),
                    Map.entry("degraded", executionPlan.degraded()),
                    Map.entry("reason_codes", executionPlan.reasonCodes())));
            log.info("intent={} scope={} confidence={} retrievalHint={} executed={} degraded={} reasons={} trace={}",
                    intent, routeDecision.scope(), routeDecision.confidence(), routeDecision.retrievalHint(),
                    executionPlan.skipRetrieval() ? "none" : executionPlan.retrievalHint(),
                    executionPlan.degraded(), executionPlan.reasonCodes(), traceId);

            // 代词/短追问需要带上上一轮主题用于检索和专家简报；直答路径仍保留原问题与历史消息。
            String executionQuestion = routingQuestion;

            if (routingPolicy.shouldClarify(routeDecision)) {
                String clarification = routingPolicy.clarificationQuestion(routeDecision);
                events.onStage("clarifying");
                events.onClarify(clarification, routingPolicy.clarificationOptions(routeDecision));
                Long messageId = persistAssistant(convId, clarification, "clarify", null, traceId,
                        clarification.length() / 2, "unavailable", "[]", claim, cancellation);
                if (messageId != null) {
                    conversations.setClarificationPending(convId, routeDecision.intent().name().toLowerCase(),
                            java.time.Instant.now().plus(Duration.ofMinutes(10)));
                    events.onDone(messageId, clarification, "unavailable", List.of());
                }
                if (messageId != null) {
                    background.submit(() -> postTurnTasks.run(convId, userId, question, clarification, traceId,
                            memoryGeneration));
                }
                return;
            }

            // --- 检索节点 (out_of_scope 跳过, 省一次embedding) ---
            List<Evidence> evidences = List.of();
            List<EpisodeStore.Episode> episodes = List.of();
            if (!executionPlan.skipRetrieval()) {
                long memoryStart = System.currentTimeMillis();
                LongTermMemoryService.RecallResult memoryRecall = longTermMemory.recall(userId, executionQuestion, traceId);
                episodes = memoryRecall.episodes();
                trace.span(traceId, convId, "memory_recall", memoryStart, memoryRecall.degraded());
                events.onStage("retrieving");
                t0 = System.currentTimeMillis();
                GraphExpansionPolicy graphPolicy = GraphExpansionPolicy.forFacets(
                        executionPlan.retrievalFacets(), executionPlan.retrievalHint());
                AgenticRetriever.RetrievalResult retrievalResult = agenticRetriever.retrieveAdaptiveResult(
                        executionQuestion, TOP_K, traceId, executionPlan.allowMultiHopEscalation(), graphPolicy,
                        GraphScope.forUser(userId, AuthContext.currentTenantId()));
                if (retrievalResult == null) {
                    throw new IllegalStateException("检索结果不能为空");
                }
                evidences = retrievalResult.evidences();
                trace.span(traceId, convId, "retrieve", t0, false, Map.ofEntries(
                        Map.entry("requested_mode", executionPlan.allowMultiHopEscalation() ? "multi_candidate" : "single"),
                        Map.entry("multi_hop_candidate", retrievalResult.multiHopCandidate()),
                        Map.entry("hops", retrievalResult.hops()),
                        Map.entry("stop_reason", retrievalResult.stopReason()),
                        Map.entry("evidence_count", evidences.size()),
                        Map.entry("graph_relations", graphPolicy.relationDescriptions()),
                        Map.entry("graph_policy", graphPolicy.policyDescriptions()),
                        Map.entry("resource_facet", executionPlan.retrievalFacets().contains(
                                com.tutor.expert.RoutingPolicy.RetrievalFacet.RESOURCE)),
                        Map.entry("retrieval_profile_version", agenticRetriever.retrievalProfileVersion()),
                        Map.entry("dense_candidate_count", retrievalResult.telemetry().denseCandidates()),
                        Map.entry("sparse_candidate_count", retrievalResult.telemetry().sparseCandidates()),
                        Map.entry("graph_candidate_count", retrievalResult.telemetry().graphCandidates()),
                        Map.entry("graph_expansion_source_count", retrievalResult.telemetry().graphExpansionSources()),
                        Map.entry("embedding_degraded", retrievalResult.telemetry().embeddingDegraded()),
                        Map.entry("sparse_degraded", retrievalResult.telemetry().sparseDegraded()),
                        Map.entry("rerank_applied", retrievalResult.telemetry().rerankApplied()),
                        Map.entry("rerank_degraded", retrievalResult.telemetry().rerankDegraded()),
                        Map.entry("final_graph_evidence_count", evidences.stream()
                                .filter(evidence -> evidence.graphPath() != null && !evidence.graphPath().isBlank()).count()),
                        Map.entry("final_direct_evidence_count", evidences.stream()
                                .filter(evidence -> evidence.graphPath() == null || evidence.graphPath().isBlank()).count()),
                        Map.entry("graph_scope", AuthContext.currentTenantId() == null
                                ? "user+public" : "user+tenant+public")));
                events.onCitations(evidences);
            }

            List<String> expertNames = ExpertRunner.expertsFor(executionPlan.intents());
            if (cancellation.isCancelled()) {
                return;
            }
            if (expertNames.isEmpty()) {
                List<ConversationStore.Msg> history = ConversationContextSelector.select(
                        recentWindow, executionQuestion, HISTORY_TURNS * 2);
                directStream(convId, userId, question, profile, evidences, episodes, history, intent, traceId,
                        memoryGeneration, events, cancellation, claim);
                return;
            }

            // --- 专家扇出节点 (简报=画像+结构化简历+证据+问题, 不带闲聊历史) ---
            String profileText = profileSection.render(new TurnContextView(profile, List.of()), tokenBudget);
            String episodeText = episodeSection.render(new TurnContextView(profile, List.of(), null, episodes), tokenBudget);
            String resumeText = resumeService.latestStructuredCompact(userId, 900); // 约300 token (实现设计3.4)
            long expertContextStart = System.currentTimeMillis();
            ExpertRunner.Briefing briefing = expertRunner.buildBriefing(
                    profileText + episodeText + '\n' + resumeText, evidences, executionQuestion);
            trace.span(traceId, convId, "expert_context", expertContextStart, false, Map.of(
                    "profile_original_tokens", briefing.usage().profileOriginalTokens(),
                    "profile_allocated_tokens", briefing.usage().profileAllocatedTokens(),
                    "evidence_original_tokens", briefing.usage().evidenceOriginalTokens(),
                    "evidence_allocated_tokens", briefing.usage().evidenceAllocatedTokens(),
                    "question_tokens", briefing.usage().questionTokens(),
                    "total_budget", briefing.usage().totalBudget(),
                    "truncated", briefing.usage().truncated()));
            for (String name : expertNames) {
                events.onStage("expert:" + name);
            }
            t0 = System.currentTimeMillis();
            List<ExpertOutput> outputs = expertRunner.run(expertNames, briefing.text(), traceId,
                    stage -> {
                        if (!cancellation.isCancelled()) {
                            events.onExpertDone(stage.expert(), stage.status(), stage.detail());
                        }
                    }, cancellation, briefing.citationIds());
            trace.span(traceId, convId, "experts", t0, outputs.size() < expertNames.size());

            if (cancellation.isCancelled()) {
                return;
            }
            // --- 仲裁节点 (流式) ---
            events.onStage("aggregating");
            long aggStart = System.currentTimeMillis();
            List<Evidence> finalEvidences = evidences;
            aggregator.aggregateStream(outputs, executionQuestion, profileText + episodeText, traceId, new Aggregator.AggregateEvents() {
                @Override public void onToken(String token) {
                    if (!cancellation.isCancelled()) events.onToken(token);
                }

                @Override public void onClarify(String q) {
                    if (!cancellation.isCancelled()) events.onClarify(q);
                }

                @Override public void onComplete(String fullText, boolean clarified) {
                    trace.span(traceId, convId, "aggregate", aggStart, clarified);
                    completeAnswer(fullText, clarified ? "clarify" : intent.name().toLowerCase(), convId, userId,
                            question, finalEvidences, briefing.citationIds(), traceId, memoryGeneration,
                            events, cancellation, claim);
                }

                @Override public void onError(Throwable error) {
                    log.error("aggregate error trace={}", traceId, error);
                    if (!cancellation.isCancelled()) events.onError("生成失败, 请稍后重试");
                }
            }, cancellation);
        } catch (Exception e) {
            log.error("turn error trace={}", traceId, e);
            if (!cancellation.isCancelled()) {
                events.onError(e instanceof IllegalStateException ? e.getMessage() : "服务异常, 请稍后重试");
            }
        }
    }

    /** 直答路径: chat/out_of_scope */
    private void directStream(long convId, long userId, String question, Map<String, Object> profile,
                              List<Evidence> evidences, List<EpisodeStore.Episode> episodes,
                              List<ConversationStore.Msg> history,
                              Intent intent, String traceId, long memoryGeneration,
                              TurnEvents events, CancellationToken cancellation, ChatTurnService.Claim claim) {
        List<ChatMessage> messages = new ArrayList<>();
        String summary = conversations.summaryState(convId).summary(); // 区5: 折叠摘要 (超12轮才有)
        long contextStart = System.currentTimeMillis();
        final PromptAssembler.Assembled assembled = promptAssembler.assembleWithMetadata(
                new TurnContextView(profile, evidences, summary, episodes), traceId);
        trace.span(traceId, convId, "context", contextStart, false, Map.of(
                "total_original_tokens", assembled.allocations().stream()
                        .mapToInt(ContextPlanner.Allocation::originalTokens).sum(),
                "total_allocated_tokens", assembled.allocations().stream()
                        .mapToInt(ContextPlanner.Allocation::allocatedTokens).sum(),
                "sections", assembled.allocations().stream().map(allocation -> Map.of(
                        "name", allocation.name(),
                        "original_tokens", allocation.originalTokens(),
                        "allocated_tokens", allocation.allocatedTokens(),
                        "dropped", allocation.dropped())).toList()));
        messages.add(SystemMessage.from(assembled.prompt()));
        for (ConversationStore.Msg m : history) {
            messages.add(m.role.equals("user") ? UserMessage.from(m.content) : AiMessage.from(m.content));
        }
        messages.add(UserMessage.from(question));

        if (toolLoopEnabled && toolCallLoop != null) {
            try {
                ToolCallLoop.LoopResult loopResult = toolCallLoop.run(Purpose.CHAT, messages, traceId,
                        new ToolExecutionContext(traceId, "chat", userId, null, false));
                completeAnswer(loopResult.answer(), intent.name().toLowerCase(), convId, userId, question,
                        evidences, assembled.citationIds(), traceId, memoryGeneration, events, cancellation, claim);
                return;
            } catch (RuntimeException error) {
                log.warn("tool loop failed, falling back to streaming direct answer trace={} type={}",
                        traceId, error.getClass().getSimpleName());
            }
        }

        StringBuilder full = new StringBuilder();
        List<Evidence> finalEvidences = evidences;
        gateway.chatStream(Purpose.CHAT, messages, traceId, new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String token) {
                full.append(token);
                if (!cancellation.isCancelled()) events.onToken(token);
            }

            @Override public void onCompleteResponse(ChatResponse response) {
                completeAnswer(full.toString(), intent.name().toLowerCase(), convId, userId, question,
                        finalEvidences, assembled.citationIds(), traceId, memoryGeneration, events, cancellation, claim);
            }

            @Override public void onError(Throwable error) {
                log.error("direct stream error trace={}", traceId, error);
                if (!cancellation.isCancelled()) events.onError("生成失败, 请稍后重试");
            }
        }, cancellation);
    }

    /**
     * 一轮回答的统一收口：引用映射 → 落库 → done 事件 → 异步后置任务。
     * 直答、工具循环与专家仲裁三条路径共用，避免其中一条漏掉画像更新或引用校验。
     * 落库返回 null 表示租约丢失或用户已取消，此时不得对外发出 done。
     */
    private void completeAnswer(String text, String intent, long convId, long userId, String question,
                                List<Evidence> evidences, Set<String> citationIds, String traceId,
                                long memoryGeneration, TurnEvents events, CancellationToken cancellation,
                                ChatTurnService.Claim claim) {
        CitationBundle bundle = citationsFor(text, evidences, citationIds);
        Long messageId = persistAssistant(convId, text, intent, bundle.json(), traceId,
                text.length() / 2, bundle.status(), bundle.issuesJson(), claim, cancellation);
        if (messageId == null) return;
        events.onDone(messageId, text, bundle.status(), parseCitationIssues(bundle.issuesJson()));
        background.submit(() -> postTurnTasks.run(convId, userId, question, text, traceId, memoryGeneration));
        background.submit(() -> verifyCitations(messageId, text,
                evidenceForCitations(evidences, citationIds), traceId));
    }

    /** 解析回答中实际使用的 [S#], 映射回 node_id 存入 citations (实现设计 3.2 引用闭环) */
    private void verifyCitations(long messageId, String text, List<Evidence> evidences, String traceId) {
        citationVerification.verify(messageId, text, evidences, traceId);
    }

    /**
     * Durable turns use a short CAS transaction which also writes the
     * assistant message. If the lease was lost or the user cancelled, no
     * assistant message is allowed to escape this method.
     */
    private Long persistAssistant(long convId, String content, String intent, String citationsJson,
                                  String traceId, int tokenCount, String citationStatus, String issuesJson,
                                  ChatTurnService.Claim claim, CancellationToken cancellation) {
        if (claim != null && chatTurns != null) {
            return chatTurns.completeWithMessage(claim, content, intent, citationsJson, tokenCount,
                    citationStatus, issuesJson).stream().boxed().findFirst().orElse(null);
        }
        if (cancellation.isCancelled()) return null;
        return conversations.appendMessage(convId, "assistant", content, intent, citationsJson, traceId,
                tokenCount, citationStatus, issuesJson);
    }

    private List<Evidence> evidenceForCitations(List<Evidence> evidences, Set<String> availableCitationIds) {
        return citations.forVerification(evidences, availableCitationIds);
    }

    private CitationBundle citationsFor(String text, List<Evidence> evidences, Set<String> availableCitationIds) {
        TurnCitations.Bundle bundle = citations.bundleFor(text, evidences, availableCitationIds);
        return new CitationBundle(bundle.json(), bundle.status(), bundle.issuesJson());
    }

    private List<String> parseCitationIssues(String issuesJson) {
        return citations.parseIssues(issuesJson);
    }

}
