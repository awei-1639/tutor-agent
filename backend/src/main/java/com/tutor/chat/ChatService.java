package com.tutor.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.context.PromptAssembler;
import com.tutor.context.TokenBudget;
import com.tutor.context.TurnContextView;
import com.tutor.context.sections.ProfileSection;
import com.tutor.auth.AuthContext;
import com.tutor.contract.Evidence;
import com.tutor.contract.ExpertOutput;
import com.tutor.contract.Intent;
import com.tutor.contract.Purpose;
import com.tutor.expert.Aggregator;
import com.tutor.expert.ExpertRunner;
import com.tutor.expert.IntentRouter;
import com.tutor.llm.LlmGateway;
import com.tutor.memory.ConversationStore;
import com.tutor.memory.EpisodeSummarizer;
import com.tutor.profile.ProfileService;
import com.tutor.retrieval.AgenticRetriever;
import com.tutor.retrieval.FusedRetriever;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 决策流编排 (V3 3.2): profile → router → {direct | experts→aggregate} → 落库 → 异步画像更新。
 * 等价 LangGraph 的图执行, 节点耗时入 turn_traces。
 */
@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final Pattern CITE = Pattern.compile("\\[S(\\d)]");
    private static final int TOP_K = 5;
    private static final int HISTORY_TURNS = 6;
    private static long currentUserId() { return AuthContext.currentUserId() == null ? 1L : AuthContext.currentUserId(); }

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
    private final com.tutor.memory.SummaryFolder summaryFolder;
    private final EpisodeSummarizer episodeSummarizer;
    private final com.tutor.guard.CitationGuard citationGuard;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    public ChatService(FusedRetriever retriever, AgenticRetriever agenticRetriever, PromptAssembler promptAssembler,
                       ProfileSection profileSection, TokenBudget tokenBudget,
                       LlmGateway gateway, ConversationStore conversations,
                       ProfileService profileService, IntentRouter router,
                       ExpertRunner expertRunner, Aggregator aggregator, TraceRecorder trace,
                       com.tutor.resume.ResumeService resumeService,
                       com.tutor.memory.SummaryFolder summaryFolder,
                       EpisodeSummarizer episodeSummarizer,
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
        this.citationGuard = citationGuard;
    }

    public interface TurnEvents {
        void onMeta(long conversationId, String traceId);
        void onStage(String phase);
        void onCitations(List<Evidence> evidences);
        void onToken(String token);
        void onClarify(String question);
        void onDone(long messageId, String fullText);
        void onError(String message);
    }

    public void turn(Long conversationId, String question, TurnEvents events) {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try {
            long convId = conversations.ensureConversation(conversationId, currentUserId());
            events.onMeta(convId, traceId);
            List<ConversationStore.Msg> history = conversations.recentMessages(convId, HISTORY_TURNS * 2);
            conversations.appendMessage(convId, "user", question, null, null, question.length() / 2);
            Map<String, Object> profile = profileService.snapshot(currentUserId());

            // --- router 节点 ---
            events.onStage("routing");
            long t0 = System.currentTimeMillis();
            List<String> recentUser = history.stream()
                    .filter(m -> m.role.equals("user")).map(m -> m.content)
                    .collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(),
                            l -> l.subList(Math.max(0, l.size() - 2), l.size())));
            Intent intent = router.route(question, recentUser, traceId);
            trace.span(traceId, convId, "router", t0, false);
            log.info("intent={} trace={}", intent, traceId);

            // --- 检索节点 (out_of_scope 跳过, 省一次embedding) ---
            List<Evidence> evidences = List.of();
            if (intent != Intent.OUT_OF_SCOPE) {
                events.onStage("retrieving");
                t0 = System.currentTimeMillis();
                evidences = agenticRetriever.retrieve(question, TOP_K, traceId);
                trace.span(traceId, convId, "retrieve", t0, false);
                events.onCitations(evidences);
            }

            List<String> expertNames = ExpertRunner.expertsFor(intent);
            if (expertNames.isEmpty()) {
                directStream(convId, question, profile, evidences, history, intent, traceId, events);
                return;
            }

            // --- 专家扇出节点 (简报=画像+结构化简历+证据+问题, 不带闲聊历史) ---
            String profileText = profileSection.render(new TurnContextView(profile, List.of()), tokenBudget);
            String resumeText = resumeService.latestStructuredCompact(currentUserId(), 900); // 约300 token (实现设计3.4)
            String briefing = expertRunner.briefing(profileText + '\n' + resumeText, evidences, question);
            for (String name : expertNames) events.onStage("expert:" + name);
            t0 = System.currentTimeMillis();
            List<ExpertOutput> outputs = expertRunner.run(expertNames, briefing, traceId,
                    name -> events.onStage("expert_done:" + name));
            trace.span(traceId, convId, "experts", t0, outputs.size() < expertNames.size());

            if (outputs.isEmpty()) { // 降级矩阵: 全部缺席 → 回落直答
                log.warn("全部专家缺席, 回落直答 trace={}", traceId);
                directStream(convId, question, profile, evidences, history, intent, traceId, events);
                return;
            }

            // --- 仲裁节点 (流式) ---
            events.onStage("aggregating");
            long aggStart = System.currentTimeMillis();
            List<Evidence> finalEvidences = evidences;
            aggregator.aggregateStream(outputs, question, profileText, traceId, new Aggregator.AggregateEvents() {
                @Override public void onToken(String token) { events.onToken(token); }

                @Override public void onClarify(String q) { events.onClarify(q); }

                @Override public void onComplete(String fullText, boolean clarified) {
                    trace.span(traceId, convId, "aggregate", aggStart, clarified);
                    long msgId = conversations.appendMessage(convId, "assistant", fullText,
                            clarified ? "clarify" : intent.name().toLowerCase(),
                            citationsJson(fullText, finalEvidences), fullText.length() / 2);
                    events.onDone(msgId, fullText);
                    background.submit(() -> profileService.updateFromMessage(currentUserId(), question, traceId));
                    background.submit(() -> summaryFolder.maybeFold(convId, traceId));
                    background.submit(() -> episodeSummarizer.maybeSummarize(convId, currentUserId(), traceId));
                    background.submit(() -> citationGuard.guard(fullText, finalEvidences, traceId));
                }

                @Override public void onError(Throwable error) {
                    log.error("aggregate error trace={}", traceId, error);
                    events.onError("生成失败, 请稍后重试");
                }
            });
        } catch (Exception e) {
            log.error("turn error trace={}", traceId, e);
            events.onError(e instanceof IllegalStateException ? e.getMessage() : "服务异常, 请稍后重试");
        }
    }

    /** 直答路径: chat/out_of_scope/专家全缺席回落 */
    private void directStream(long convId, String question, Map<String, Object> profile,
                              List<Evidence> evidences, List<ConversationStore.Msg> history,
                              Intent intent, String traceId, TurnEvents events) {
        List<ChatMessage> messages = new ArrayList<>();
        String summary = conversations.summaryState(convId).summary(); // 区5: 折叠摘要 (超12轮才有)
        messages.add(SystemMessage.from(
                promptAssembler.assemble(new TurnContextView(profile, evidences, summary), traceId)));
        for (ConversationStore.Msg m : history) {
            messages.add(m.role.equals("user") ? UserMessage.from(m.content) : AiMessage.from(m.content));
        }
        messages.add(UserMessage.from(question));

        StringBuilder full = new StringBuilder();
        List<Evidence> finalEvidences = evidences;
        gateway.chatStream(Purpose.CHAT, messages, traceId, new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String token) {
                full.append(token);
                events.onToken(token);
            }

            @Override public void onCompleteResponse(ChatResponse response) {
                String text = full.toString();
                long msgId = conversations.appendMessage(convId, "assistant", text,
                        intent.name().toLowerCase(), citationsJson(text, finalEvidences), text.length() / 2);
                events.onDone(msgId, text);
                background.submit(() -> profileService.updateFromMessage(currentUserId(), question, traceId));
                background.submit(() -> summaryFolder.maybeFold(convId, traceId));
                    background.submit(() -> episodeSummarizer.maybeSummarize(convId, currentUserId(), traceId));
                    background.submit(() -> citationGuard.guard(text, finalEvidences, traceId));
            }

            @Override public void onError(Throwable error) {
                log.error("direct stream error trace={}", traceId, error);
                events.onError("生成失败, 请稍后重试");
            }
        });
    }

    /** 解析回答中实际使用的 [S#], 映射回 node_id 存入 citations (实现设计 3.2 引用闭环) */
    private String citationsJson(String text, List<Evidence> evidences) {
        Set<String> used = new LinkedHashSet<>();
        Matcher m = CITE.matcher(text);
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1)) - 1;
            if (idx >= 0 && idx < evidences.size()) used.add(evidences.get(idx).nodeId());
        }
        try {
            return mapper.writeValueAsString(used);
        } catch (Exception e) {
            return "[]";
        }
    }
}
