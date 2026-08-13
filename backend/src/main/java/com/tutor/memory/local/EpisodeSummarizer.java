package com.tutor.memory.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.Purpose;
import com.tutor.llm.LlmGateway;
import com.tutor.memory.local.ConversationStore;
import com.tutor.memory.local.EpisodeStore;
import com.tutor.memory.policy.MemoryAdmissionPolicy;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final String SYS = """
            你是会话摘要器。把对话提炼为 JSON {"summary":"...","topics":["..."],"open_items":["..."]}:
            - summary ≤200字, 第三人称, 仅保留用户明确陈述的目标、偏好、经验和未决问题
            - topics 3-5 个关键词 (技术领域/学习方法/具体技能)
            - open_items 用户尚未完成的事或下次该继续的问题
            只输出 JSON。
            """;

    private final LlmGateway gateway;
    private final EpisodeStore store;
    private final ConversationStore conversations;
    private final EpisodeCommitter committer;
    private final MemoryAdmissionPolicy admission;
    private final ObjectMapper mapper = new ObjectMapper();

    public EpisodeSummarizer(LlmGateway gateway, EpisodeStore store, ConversationStore conversations,
                             EpisodeCommitter committer, MemoryAdmissionPolicy admission) {
        this.gateway = gateway;
        this.store = store;
        this.conversations = conversations;
        this.committer = committer;
        this.admission = admission;
    }

    /**
     * 生成 episode 并入库。会话 ≥6 轮 (12 条消息) 才调用, 节省 LLM 成本。
     * 失败仅 log, 不抛。
     */
    public void maybeSummarize(long conversationId, long userId, String traceId) {
        try {
            long watermark = conversations.episodeUptoMsgId(conversationId);
            List<ConversationStore.Msg> msgs = conversations.messagesAfter(conversationId, watermark, 30);
            // Only summarize newly completed turns; this avoids repeatedly embedding the same conversation window.
            if (msgs.size() < 4) return;

            StringBuilder convo = new StringBuilder();
            for (var m : msgs) {
                // Durable facts must originate with the user. Assistant output is
                // intentionally excluded so a hallucination cannot reinforce itself.
                if (!m.role.equals("user")) continue;
                convo.append("用户: ")
                     .append(m.content, 0, Math.min(m.content.length(), 400))
                     .append('\n');
            }
            if (convo.isEmpty()) return;

            String json = gateway.chatJson(Purpose.SUMMARY,
                    List.of(SystemMessage.from(SYS), UserMessage.from(convo.toString())), traceId);
            var node = mapper.readTree(json);
            String summary = node.path("summary").asText("");
            if (summary.isBlank()) return;

            List<String> topics = new java.util.ArrayList<>();
            node.path("topics").forEach(t -> topics.add(t.asText("")));
            List<String> open = new java.util.ArrayList<>();
            node.path("open_items").forEach(t -> open.add(t.asText("")));
            if (!admission.acceptsEpisode(summary, topics, open)) return;

            // 计算 embedding (summary 文本)
            float[] emb = gateway.embed("情景摘要: " + summary, traceId);
            if (committer.commit(userId, conversationId, watermark, msgs.getFirst().id, msgs.getLast().id,
                    summary, topics, open, emb)) {
                log.info("episode 入库 conv={} topics={} trace={}", conversationId, topics.size(), traceId);
            }
        } catch (Exception e) {
            log.error("episode 生成失败(不影响对话) conv={}: {}", conversationId, e.getMessage());
        }
    }
}
