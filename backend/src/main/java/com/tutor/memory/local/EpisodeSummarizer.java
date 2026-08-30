package com.tutor.memory.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.Purpose;
import com.tutor.llm.EmbeddingGateway;
import com.tutor.llm.JsonGenerationGateway;
import com.tutor.llm.structured.EpisodeSummaryOutput;
import com.tutor.llm.structured.StructuredOutputResult;
import com.tutor.llm.structured.StructuredOutputService;
import com.tutor.llm.structured.StructuredTask;
import com.tutor.memory.local.ConversationStore;
import com.tutor.memory.local.EpisodeStore;
import com.tutor.memory.policy.MemoryAdmissionPolicy;
import com.tutor.resume.PiiMasker;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
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

    public EpisodeSummarizer(JsonGenerationGateway jsonGateway, EmbeddingGateway embeddingGateway,
                             EpisodeStore store, ConversationStore conversations,
                             EpisodeCommitter committer, MemoryAdmissionPolicy admission) {
        this(jsonGateway, embeddingGateway, store, conversations, committer, admission,
                DEFAULT_MIN_NEW_MESSAGES, new StructuredOutputService(jsonGateway, null), null);
    }

    public EpisodeSummarizer(JsonGenerationGateway jsonGateway, EmbeddingGateway embeddingGateway,
                             EpisodeStore store, ConversationStore conversations,
                             EpisodeCommitter committer, MemoryAdmissionPolicy admission,
                             @Value("${memory.episode.min-new-messages:12}") int minNewMessages) {
        this(jsonGateway, embeddingGateway, store, conversations, committer, admission,
                minNewMessages, new StructuredOutputService(jsonGateway, null), null);
    }

    @Autowired
    public EpisodeSummarizer(JsonGenerationGateway jsonGateway, EmbeddingGateway embeddingGateway,
                             EpisodeStore store, ConversationStore conversations,
                             EpisodeCommitter committer, MemoryAdmissionPolicy admission,
                             @Value("${memory.episode.min-new-messages:12}") int minNewMessages,
                             StructuredOutputService structuredOutputService,
                             FactExtractionService factExtraction) {
        this.jsonGateway = jsonGateway;
        this.embeddingGateway = embeddingGateway;
        this.store = store;
        this.conversations = conversations;
        this.committer = committer;
        this.admission = admission;
        this.minNewMessages = Math.max(2, minNewMessages);
        this.structuredOutputService = structuredOutputService;
        this.factExtraction = factExtraction;
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
            List<ConversationStore.Msg> msgs = conversations.messagesAfter(conversationId, watermark, 30);
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

            String safeConversation = PiiMasker.mask(convo.toString()).masked();
            StructuredOutputResult<EpisodeSummaryOutput> structured = structuredOutputService.generate(
                    StructuredTask.EPISODE_SUMMARY,
                    Purpose.SUMMARY,
                    List.of(SystemMessage.from(SYS), UserMessage.from(safeConversation)),
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
