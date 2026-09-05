package com.tutor.conversation.memory.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.Purpose;
import com.tutor.platform.llm.EmbeddingGateway;
import com.tutor.platform.llm.JsonGenerationGateway;
import com.tutor.platform.llm.structured.EpisodeSummaryOutput;
import com.tutor.platform.llm.structured.StructuredOutputResult;
import com.tutor.platform.llm.structured.StructuredOutputService;
import com.tutor.platform.llm.structured.StructuredTask;
import com.tutor.conversation.memory.local.ConversationStore;
import com.tutor.conversation.memory.local.EpisodeStore;
import com.tutor.conversation.memory.policy.MemoryAdmissionPolicy;
import com.tutor.identity.resume.PiiMasker;
import com.tutor.platform.llm.LlmMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * L2 情景记忆生成器 (Phase 3 V4 3.1): 会话末提取结构化摘要 → episodes 表。
 * 异步执行, 失败只记日志 (铁律: 记忆写入永不阻塞回答)。
 * 触发: 会话累计 ≥6 轮 (12 条消息) OR 用户显式结束。
 */
@Component
public class EpisodeSummarizer {
    private static final Logger log = LoggerFactory.getLogger(EpisodeSummarizer.class);
    private static final int DEFAULT_MIN_NEW_MESSAGES = 12;

    private static final String SYS = """
            你是会话摘要器。把对话提炼为 JSON {"summary":"...","topics":["..."],"open_items":["..."]}:
            - summary ≤200字, 第三人称, 仅保留用户明确陈述的目标、偏好、经验和未决问题
            - topics 3-5 个关键词 (技术领域/学习方法/具体技能)
            - open_items 用户尚未完成的事或下次该继续的问题
            只输出 JSON。
            """;

    private final JsonGenerationGateway jsonGateway;
    private final EmbeddingGateway embeddingGateway;
    private final EpisodeStore store;
    private final ConversationStore conversations;
    private final EpisodeCommitter committer;
    private final MemoryAdmissionPolicy admission;
    private final int minNewMessages;
    private final ObjectMapper mapper = new ObjectMapper();
    private final StructuredOutputService structuredOutputService;
    private final FactExtractionService factExtraction;
    private final com.tutor.conversation.memory.policy.MemoryImportanceGate importanceGate;
    private final boolean importanceGateEnabled;
    /** 单次抽取的源窗口上限；窗口填满后门控不能再"等累积", 否则会永久卡在同一批消息上。 */
    static final int MAX_WINDOW_MESSAGES = 30;

    public EpisodeSummarizer(JsonGenerationGateway jsonGateway, EmbeddingGateway embeddingGateway,
                             EpisodeStore store, ConversationStore conversations,
                             EpisodeCommitter committer, MemoryAdmissionPolicy admission) {
        this(jsonGateway, embeddingGateway, store, conversations, committer, admission,
                DEFAULT_MIN_NEW_MESSAGES, new StructuredOutputService(jsonGateway, null), null,
                new com.tutor.conversation.memory.policy.MemoryImportanceGate(), true);
    }

    public EpisodeSummarizer(JsonGenerationGateway jsonGateway, EmbeddingGateway embeddingGateway,
                             EpisodeStore store, ConversationStore conversations,
                             EpisodeCommitter committer, MemoryAdmissionPolicy admission,
                             @Value("${memory.episode.min-new-messages:12}") int minNewMessages) {
        this(jsonGateway, embeddingGateway, store, conversations, committer, admission,
                minNewMessages, new StructuredOutputService(jsonGateway, null), null,
                new com.tutor.conversation.memory.policy.MemoryImportanceGate(), true);
    }

    @Autowired
    public EpisodeSummarizer(JsonGenerationGateway jsonGateway, EmbeddingGateway embeddingGateway,
                             EpisodeStore store, ConversationStore conversations,
                             EpisodeCommitter committer, MemoryAdmissionPolicy admission,
                             @Value("${memory.episode.min-new-messages:12}") int minNewMessages,
                             StructuredOutputService structuredOutputService,
                             FactExtractionService factExtraction,
                             com.tutor.conversation.memory.policy.MemoryImportanceGate importanceGate,
                             @Value("${memory.episode.importance-gate-enabled:true}") boolean importanceGateEnabled) {
        this.jsonGateway = jsonGateway;
        this.embeddingGateway = embeddingGateway;
        this.store = store;
        this.conversations = conversations;
        this.committer = committer;
        this.admission = admission;
        this.minNewMessages = Math.max(2, minNewMessages);
        this.structuredOutputService = structuredOutputService;
        this.factExtraction = factExtraction;
        this.importanceGate = importanceGate;
        this.importanceGateEnabled = importanceGateEnabled;
    }

    /**
     * 生成 episode 并入库。会话 ≥6 轮 (12 条消息) 才调用, 节省 LLM 成本。
     * 失败仅 log, 不抛。
     */
    public void maybeSummarize(long conversationId, long userId, String traceId) {
        maybeSummarizeInternal(conversationId, userId, traceId, Long.MIN_VALUE);
    }

    /** 供请求流水线使用的变体；expectedGeneration 用于在删除后围栏已排队的工作。 */
    public void maybeSummarize(long conversationId, long userId, String traceId, long expectedGeneration) {
        maybeSummarizeInternal(conversationId, userId, traceId, expectedGeneration);
    }

    private void maybeSummarizeInternal(long conversationId, long userId, String traceId, long expectedGeneration) {
        try {
            long watermark = conversations.episodeUptoMsgId(conversationId);
            List<ConversationStore.Msg> msgs = conversations.messagesAfter(conversationId, watermark, MAX_WINDOW_MESSAGES);
            // 仅摘要新完成的轮次，避免反复为同一会话窗口计算 Embedding。
            if (msgs.size() < minNewMessages) return;

            StringBuilder convo = new StringBuilder();
            for (var m : msgs) {
                // 持久事实必须源自用户；刻意排除助手输出，避免幻觉内容自我强化。
                if (!m.role.equals("user")) continue;
                convo.append("用户: ")
                     .append(m.content, 0, Math.min(m.content.length(), 400))
                     .append('\n');
            }
            if (convo.isEmpty()) return;
            // 重要性门控：无显著信号的窗口不烧 LLM。窗口未满时不推进水位线, 等后续消息累积再试；
            // 窗口已满则必须推进, 否则纯闲聊满 30 条后每次都读到同一批消息, 该会话记忆永久停摆。
            if (importanceGateEnabled && !importanceGate.hasSalientSignal(convo.toString())) {
                if (msgs.size() >= MAX_WINDOW_MESSAGES) {
                    conversations.advanceEpisodeWatermark(conversationId, msgs.getLast().id);
                    log.info("episode 跳过整窗(无显著信号, 窗口已满) conv={} upto={} trace={}",
                            conversationId, msgs.getLast().id, traceId);
                }
                return;
            }

            String safeConversation = PiiMasker.mask(convo.toString()).masked();
            StructuredOutputResult<EpisodeSummaryOutput> structured = structuredOutputService.generate(
                    StructuredTask.EPISODE_SUMMARY,
                    Purpose.SUMMARY,
                    List.of(LlmMessage.system(SYS), LlmMessage.user(safeConversation)),
                    EpisodeSummaryOutput.class,
                    output -> {
                        if (output.summary() == null || output.summary().isBlank()
                                || output.topics() == null || output.topics().isEmpty()
                                || output.openItems() == null) {
                            throw new IllegalArgumentException("episode summary contract invalid");
                        }
                    },
                    traceId
            );
            if (!structured.success()) return;
            EpisodeSummaryOutput output = structured.value();
            String summary = output.summary();
            List<String> topics = output.topics();
            List<String> open = output.openItems();
            if (!admission.acceptsEpisode(summary, topics, open)) return;

            // 计算 embedding (summary 文本)
            float[] emb = embeddingGateway.embed("情景摘要: " + summary, traceId);
            long episodeId = committer.commitReturningId(userId, conversationId, watermark,
                    msgs.getFirst().id, msgs.getLast().id, summary, topics, open, emb, expectedGeneration);
            if (episodeId > 0) {
                log.info("episode 入库 conv={} topics={} trace={}", conversationId, topics.size(), traceId);
                if (factExtraction != null) {
                    // 复用同一批已脱敏的源窗口文本；失败只记日志，不影响 Episode。
                    factExtraction.extractFromWindow(userId, episodeId, expectedGeneration,
                            safeConversation, traceId);
                }
            }
        } catch (Exception e) {
            log.error("episode 生成失败(不影响对话) conv={}: {}", conversationId, e.getMessage());
        }
    }
}
