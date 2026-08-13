package com.tutor.chat.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.context.PromptAssembler;
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
import com.tutor.expert.Aggregator;
import com.tutor.expert.ExpertRunner;
import com.tutor.expert.IntentRouter;
import com.tutor.guard.CitationSourcePolicy;
import com.tutor.llm.LlmGateway;
import com.tutor.memory.application.LongTermMemoryService;
import com.tutor.memory.local.ConversationStore;
import com.tutor.memory.local.EpisodeStore;
import com.tutor.memory.local.EpisodeSummarizer;
import com.tutor.memory.local.SummaryFolder;
import com.tutor.profile.ProfileService;
import com.tutor.retrieval.agentic.AgenticRetriever;
import com.tutor.retrieval.fusion.FusedRetriever;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
    private final LlmGateway gateway;
    private final ConversationStore conversations;
    private final ProfileService profileService;
    private final IntentRouter router;
    private final ExpertRunner expertRunner;
    private final Aggregator aggregator;
    private final TraceRecorder trace;
    private final com.tutor.resume.ResumeService resumeService;
    private final SummaryFolder summaryFolder;
    private final EpisodeSummarizer episodeSummarizer;
    private final LongTermMemoryService longTermMemory;
    private final com.tutor.context.sections.EpisodeSection episodeSection;
    private final com.tutor.guard.CitationGuard citationGuard;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    private record CitationBundle(String json, String status, String issuesJson) {}

    public ChatService(FusedRetriever retriever, AgenticRetriever agenticRetriever, PromptAssembler promptAssembler,
                       ProfileSection profileSection, TokenBudget tokenBudget,
                       LlmGateway gateway, ConversationStore conversations,
                       ProfileService profileService, IntentRouter router,
                       ExpertRunner expertRunner, Aggregator aggregator, TraceRecorder trace,
                       com.tutor.resume.ResumeService resumeService,
                       SummaryFolder summaryFolder,
                       EpisodeSummarizer episodeSummarizer,
                       LongTermMemoryService longTermMemory,
                       com.tutor.context.sections.EpisodeSection episodeSection,
                       com.tutor.guard.CitationGuard citationGuard) {
        this.retriever = retriever;
        this.agenticRetriever = agenticRetriever;
        this.promptAssembler = promptAssembler;
        this.profileSection = profileSection;
        this.tokenBudget = tokenBudget;
        this.gateway = gateway;
        this.conversations = conversations;
        this.profileService = profileService;
        this.router = router;
        this.expertRunner = expertRunner;
        this.aggregator = aggregator;
        this.trace = trace;
        this.resumeService = resumeService;
        this.summaryFolder = summaryFolder;
        this.episodeSummarizer = episodeSummarizer;
        this.longTermMemory = longTermMemory;
        this.episodeSection = episodeSection;
        this.citationGuard = citationGuard;
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
        Objects.requireNonNull(cancellation, "cancellation");
        if (cancellation.isCancelled()) {
            return;
        }
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try {
            // LLM 回调和后台任务会切换线程；在请求线程中一次性固定身份，不能在回调中再读 ThreadLocal。
            long userId = currentUserId();
            long convId = conversations.ensureConversation(conversationId, userId);
            events.onMeta(convId, traceId);
            List<ConversationStore.Msg> history = conversations.recentMessages(convId, HISTORY_TURNS * 2);
            conversations.appendMessage(convId, "user", question, null, null, traceId, question.length() / 2);
            Map<String, Object> profile = profileService.snapshot(userId);

            // --- router 节点 ---
            events.onStage("routing");
            long t0 = System.currentTimeMillis();
            List<String> recentUser = history.stream()
                    .filter(m -> "user".equals(m.role)).map(m -> m.content)
                    .collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(),
                            l -> l.subList(Math.max(0, l.size() - 2), l.size())));
            Intent intent = router.route(question, recentUser, traceId);
            trace.span(traceId, convId, "router", t0, false);
            log.info("intent={} trace={}", intent, traceId);

            // --- 检索节点 (out_of_scope 跳过, 省一次embedding) ---
            List<Evidence> evidences = List.of();
            List<EpisodeStore.Episode> episodes = List.of();
            if (intent != Intent.OUT_OF_SCOPE) {
                long memoryStart = System.currentTimeMillis();
                LongTermMemoryService.RecallResult memoryRecall = longTermMemory.recall(userId, question, traceId);
                episodes = memoryRecall.episodes();
                trace.span(traceId, convId, "memory_recall", memoryStart, memoryRecall.degraded());
                events.onStage("retrieving");
                t0 = System.currentTimeMillis();
                evidences = agenticRetriever.retrieve(question, TOP_K, traceId);
                trace.span(traceId, convId, "retrieve", t0, false);
                events.onCitations(evidences);
            }

            List<String> expertNames = ExpertRunner.expertsFor(intent);
            if (cancellation.isCancelled()) {
                return;
            }
            if (expertNames.isEmpty()) {
                directStream(convId, userId, question, profile, evidences, episodes, history, intent, traceId, events, cancellation);
                return;
            }

            // --- 专家扇出节点 (简报=画像+结构化简历+证据+问题, 不带闲聊历史) ---
            String profileText = profileSection.render(new TurnContextView(profile, List.of()), tokenBudget);
            String episodeText = episodeSection.render(new TurnContextView(profile, List.of(), null, episodes), tokenBudget);
            String resumeText = resumeService.latestStructuredCompact(userId, 900); // 约300 token (实现设计3.4)
            ExpertRunner.Briefing briefing = expertRunner.buildBriefing(
                    profileText + episodeText + '\n' + resumeText, evidences, question);
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
            aggregator.aggregateStream(outputs, question, profileText + episodeText, traceId, new Aggregator.AggregateEvents() {
                @Override public void onToken(String token) {
                    if (!cancellation.isCancelled()) events.onToken(token);
                }

                @Override public void onClarify(String q) {
                    if (!cancellation.isCancelled()) events.onClarify(q);
                }

                @Override public void onComplete(String fullText, boolean clarified) {
                    trace.span(traceId, convId, "aggregate", aggStart, clarified);
                    CitationBundle citationBundle = citationsFor(fullText, finalEvidences, briefing.citationIds());
                    long msgId = conversations.appendMessage(convId, "assistant", fullText,
                            clarified ? "clarify" : intent.name().toLowerCase(),
                            citationBundle.json(), traceId, fullText.length() / 2,
                            citationBundle.status(), citationBundle.issuesJson());
                    if (!cancellation.isCancelled()) events.onDone(msgId, fullText,
                            citationBundle.status(), parseCitationIssues(citationBundle.issuesJson()));
                    background.submit(() -> profileService.updateFromMessage(userId, question, traceId));
                    background.submit(() -> summaryFolder.maybeFold(convId, traceId));
                    background.submit(() -> episodeSummarizer.maybeSummarize(convId, userId, traceId));
                    background.submit(() -> longTermMemory.remember(userId, question, fullText, traceId));
                    background.submit(() -> verifyCitations(msgId, fullText,
                            evidenceForCitations(finalEvidences, briefing.citationIds()), traceId));
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
                              Intent intent, String traceId, TurnEvents events, CancellationToken cancellation) {
        List<ChatMessage> messages = new ArrayList<>();
        String summary = conversations.summaryState(convId).summary(); // 区5: 折叠摘要 (超12轮才有)
        PromptAssembler.Assembled assembledCandidate = promptAssembler.assembleWithMetadata(
                new TurnContextView(profile, evidences, summary, episodes), traceId);
        // Keep the legacy text-only seam usable for lightweight test doubles and
        // older adapters while production always returns metadata.
        if (assembledCandidate == null) {
            assembledCandidate = new PromptAssembler.Assembled(
                    promptAssembler.assemble(new TurnContextView(profile, evidences, summary, episodes), traceId),
                    Set.of());
        }
        final PromptAssembler.Assembled assembled = assembledCandidate;
        messages.add(SystemMessage.from(assembled.prompt()));
        for (ConversationStore.Msg m : history) {
            messages.add(m.role.equals("user") ? UserMessage.from(m.content) : AiMessage.from(m.content));
        }
        messages.add(UserMessage.from(question));

        StringBuilder full = new StringBuilder();
        List<Evidence> finalEvidences = evidences;
        gateway.chatStream(Purpose.CHAT, messages, traceId, new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String token) {
                full.append(token);
                if (!cancellation.isCancelled()) events.onToken(token);
            }

            @Override public void onCompleteResponse(ChatResponse response) {
                String text = full.toString();
                CitationBundle citationBundle = citationsFor(text, finalEvidences, assembled.citationIds());
                long msgId = conversations.appendMessage(convId, "assistant", text,
                        intent.name().toLowerCase(), citationBundle.json(), traceId, text.length() / 2,
                        citationBundle.status(), citationBundle.issuesJson());
                if (!cancellation.isCancelled()) events.onDone(msgId, text,
                        citationBundle.status(), parseCitationIssues(citationBundle.issuesJson()));
                background.submit(() -> profileService.updateFromMessage(userId, question, traceId));
                background.submit(() -> summaryFolder.maybeFold(convId, traceId));
                background.submit(() -> episodeSummarizer.maybeSummarize(convId, userId, traceId));
                background.submit(() -> longTermMemory.remember(userId, question, text, traceId));
                background.submit(() -> verifyCitations(msgId, text,
                        evidenceForCitations(finalEvidences, assembled.citationIds()), traceId));
            }

            @Override public void onError(Throwable error) {
                log.error("direct stream error trace={}", traceId, error);
                if (!cancellation.isCancelled()) events.onError("生成失败, 请稍后重试");
            }
        }, cancellation);
    }

    /** 解析回答中实际使用的 [S#], 映射回 node_id 存入 citations (实现设计 3.2 引用闭环) */
    private void verifyCitations(long messageId, String text, List<Evidence> evidences, String traceId) {
        try {
            com.tutor.guard.CitationGuard.GuardResult result = citationGuard.guard(text, evidences, traceId);
            if (result == null) throw new IllegalStateException("citation guard returned null");
            String issuesJson = mapper.writeValueAsString(result.issues() == null ? List.of() : result.issues());
            conversations.updateCitationVerification(messageId,
                    result.status() == null ? "unavailable" : result.status(), issuesJson);
        } catch (Exception error) {
            log.warn("引用校验结果不可用 trace={}", traceId, error);
            try {
                conversations.updateCitationVerification(messageId, "unavailable", "[]");
            } catch (Exception persistenceError) {
                log.warn("引用校验降级状态写入失败 trace={}", traceId, persistenceError);
            }
        }
    }

    private List<Evidence> evidenceForCitations(List<Evidence> evidences, Set<String> availableCitationIds) {
        if (evidences == null || evidences.isEmpty()) return List.of();
        List<Evidence> bounded = new ArrayList<>(evidences.subList(0, Math.min(evidences.size(), 10)));
        for (int i = 0; i < bounded.size(); i++) {
            if (availableCitationIds == null || !availableCitationIds.contains("S" + (i + 1))) {
                bounded.set(i, null);
            }
        }
        return java.util.Collections.unmodifiableList(bounded);
    }

    private CitationBundle citationsFor(String text, List<Evidence> evidences, Set<String> availableCitationIds) {
        Set<Integer> used = new LinkedHashSet<>();
        Set<String> invalid = new LinkedHashSet<>();
        Matcher m = CITE.matcher(text);
        while (m.find()) {
            int idx = parseCitationIndex(m.group(1));
            if (idx >= 0 && idx < Math.min(evidences.size(), 10)
                    && availableCitationIds != null && availableCitationIds.contains("S" + (idx + 1))
                    && evidences.get(idx) != null) {
                used.add(idx);
            } else {
                invalid.add("S" + m.group(1));
            }
        }
        try {
            // 保留卡片所需的完整引用信息，历史会话恢复后也能查看溯源。
            String json = mapper.writeValueAsString(used.stream().map(idx -> {
                Evidence e = evidences.get(idx);
                CitationSourcePolicy.Provenance provenance = CitationSourcePolicy.inspect(e);
                String[] parts = e.chunkText().split("\\|", 3);
                return Map.of(
                        "sid", "S" + (idx + 1),
                        "node_id", e.nodeId(),
                        "type", e.nodeType(),
                        "title", parts.length > 1 ? parts[1] : e.nodeId(),
                        "text", e.chunkText(),
                        "graph_path", e.graphPath() == null ? "" : e.graphPath(),
                        "source_url", provenance.sourceUrl(),
                        "source_status", provenance.sourceStatus(),
                        "evidence_hash", provenance.evidenceHash());
            }).toList());
            String status = !invalid.isEmpty() ? "invalid_reference" : used.isEmpty() ? "not_applicable" : "pending";
            return new CitationBundle(json, status, mapper.writeValueAsString(invalid));
        } catch (Exception e) {
            return new CitationBundle("[]", "unavailable", "[]");
        }
    }

    private List<String> parseCitationIssues(String issuesJson) {
        if (issuesJson == null || issuesJson.isBlank()) return List.of();
        try {
            var node = mapper.readTree(issuesJson);
            if (!node.isArray()) return List.of();
            List<String> issues = new ArrayList<>();
            node.forEach(item -> {
                if (item.isTextual() && issues.size() < 20) issues.add(item.asText());
            });
            return List.copyOf(issues);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private int parseCitationIndex(String digits) {
        try {
            return Math.subtractExact(Integer.parseInt(digits), 1);
        } catch (NumberFormatException | ArithmeticException ignored) {
            return -1;
        }
    }

}
