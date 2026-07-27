package com.tutor.memory;

import com.tutor.contract.Purpose;
import com.tutor.llm.LlmGateway;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
    private final LlmGateway gateway;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public SummaryFolder(ConversationStore store, LlmGateway gateway) {
        this.store = store;
        this.gateway = gateway;
    }

    /** 回答完成后由后台线程调用 */
    public void maybeFold(long conversationId, String traceId) {
        try {
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
                sb.append(m.role().equals("user") ? "用户: " : "助手: ")
                        .append(m.content(), 0, Math.min(m.content().length(), 500)).append('\n');
            }
            String json = gateway.chatJson(Purpose.SUMMARY,
                    List.of(SystemMessage.from(SYS), UserMessage.from(sb.toString())), traceId);
            String summary = mapper.readTree(json).path("summary").asText("");
            if (summary.isBlank()) return;
            store.saveSummary(conversationId, summary, store.maxFoldableMsgId(conversationId, KEEP_RECENT_MESSAGES));
            log.info("会话摘要折叠 conv={} folded={} trace={}", conversationId, toFold.size(), traceId);
        } catch (Exception e) {
            log.error("摘要折叠失败(不影响对话) conv={}: {}", conversationId, e.getMessage());
        }
    }
}
