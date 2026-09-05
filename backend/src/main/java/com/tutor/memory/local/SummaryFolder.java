package com.tutor.memory.local;

import com.tutor.contract.Purpose;
import com.tutor.llm.JsonGenerationGateway;
import com.tutor.llm.structured.StructuredOutputResult;
import com.tutor.llm.structured.StructuredOutputService;
import com.tutor.llm.structured.StructuredTask;
import com.tutor.llm.structured.SummaryOutput;
import com.tutor.memory.local.ConversationStore;
import com.tutor.memory.policy.MemoryAdmissionPolicy;
import com.tutor.resume.PiiMasker;
import com.tutor.llm.LlmMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * L1 滚动摘要折叠 (实现设计 2.1): 历史超过阈值时, 最老轮次由LLM增量折叠进 conversations.summary。
 * 异步执行, 失败只记日志 (铁律: 记忆写入永不阻塞回答)。
 */
@Component
public class SummaryFolder {
    private static final Logger log = LoggerFactory.getLogger(SummaryFolder.class);
    static final int KEEP_RECENT_MESSAGES = 12;   // 最近6轮(12条)保留原文
    static final int FOLD_TRIGGER_MESSAGES = 20;  // 总量超过此值才折叠

    private static final String SYS = """
            你是对话摘要器。把「已有摘要」与「新增对话」增量合并为一份新摘要, 输出JSON {"summary":"..."}:
            - 300字以内, 保留: 用户的关键事实/目标/偏好、已给出的重要建议与结论、未决事项
            - 丢弃寒暄与重复; 用第三人称陈述
            """;

    private final ConversationStore store;
    private final JsonGenerationGateway gateway;
    private final MemoryAdmissionPolicy admission;
    private final StructuredOutputService structuredOutputService;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public SummaryFolder(ConversationStore store, JsonGenerationGateway gateway) {
        this(store, gateway, new MemoryAdmissionPolicy(), new StructuredOutputService(gateway, null));
    }

    public SummaryFolder(ConversationStore store, JsonGenerationGateway gateway, MemoryAdmissionPolicy admission) {
        this(store, gateway, admission, new StructuredOutputService(gateway, null));
    }

    @Autowired
    public SummaryFolder(ConversationStore store, JsonGenerationGateway gateway,
                         MemoryAdmissionPolicy admission,
                         StructuredOutputService structuredOutputService) {
        this.store = store;
        this.gateway = gateway;
        this.admission = admission;
        this.structuredOutputService = structuredOutputService;
    }

    /** 回答完成后由后台线程调用 */
    public void maybeFold(long conversationId, String traceId) {
        maybeFoldInternal(conversationId, 0L, Long.MIN_VALUE, traceId);
    }

    /** Fenced variant used by the request pipeline. */
    public void maybeFold(long conversationId, long userId, long expectedGeneration, String traceId) {
        maybeFoldInternal(conversationId, userId, expectedGeneration, traceId);
    }

    private void maybeFoldInternal(long conversationId, long userId, long expectedGeneration, String traceId) {
        try {
            if (expectedGeneration != Long.MIN_VALUE
                    && !store.memoryGenerationCurrent(conversationId, userId, expectedGeneration)) return;
            ConversationStore.SummaryState state = store.summaryState(conversationId);
            List<ConversationStore.Msg> toFold = store.messagesToFold(
                    conversationId, state.uptoMsgId(), KEEP_RECENT_MESSAGES);
            // 触发条件: 有可折叠内容且窗口外消息达到一定量 (避免每轮都折叠)
            if (toFold.size() < FOLD_TRIGGER_MESSAGES - KEEP_RECENT_MESSAGES) return;

            StringBuilder sb = new StringBuilder();
            if (state.summary() != null && !state.summary().isBlank()) {
                sb.append("已有摘要:\n").append(state.summary()).append("\n\n");
            }
            sb.append("新增对话:\n");
            for (ConversationStore.Msg m : toFold) {
                sb.append(m.role.equals("user") ? "用户: " : "助手: ")
                        .append(m.content, 0, Math.min(m.content.length(), 500)).append('\n');
            }
            String safePrompt = PiiMasker.mask(sb.toString()).masked();
            StructuredOutputResult<SummaryOutput> structured = structuredOutputService.generate(
                    StructuredTask.SUMMARY_FOLDER,
                    Purpose.SUMMARY,
                    List.of(LlmMessage.system(SYS), LlmMessage.user(safePrompt)),
                    SummaryOutput.class,
                    output -> {
                        if (output.summary() == null || output.summary().isBlank()) {
                            throw new IllegalArgumentException("summary is blank");
                        }
                    },
                    traceId
            );
            if (!structured.success()) return;
            String summary = structured.value().summary();
            PiiMasker.MaskResult safeSummary = PiiMasker.mask(summary);
            if (!admission.acceptsSummary(safeSummary.masked())) return;
            if (expectedGeneration != Long.MIN_VALUE
                    && !store.memoryGenerationCurrent(conversationId, userId, expectedGeneration)) return;
            long upto = store.maxFoldableMsgId(conversationId, KEEP_RECENT_MESSAGES);
            if (expectedGeneration == Long.MIN_VALUE) {
                store.saveSummary(conversationId, safeSummary.masked(), upto);
            } else if (!store.saveSummaryIfGeneration(conversationId, userId, expectedGeneration,
                    safeSummary.masked(), upto)) {
                return;
            }
            log.info("会话摘要折叠 conv={} folded={} trace={}", conversationId, toFold.size(), traceId);
        } catch (Exception e) {
            log.error("摘要折叠失败(不影响对话) conv={}: {}", conversationId, e.getMessage());
        }
    }
}
