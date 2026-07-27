package com.tutor.expert;

import com.tutor.contract.ExpertOutput;
import com.tutor.contract.Purpose;
import com.tutor.llm.LlmGateway;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 仲裁融合器 (V3 3.2): 汇总专家结构化输出为统一行动方案, 保留[S#]引用。
 * 分歧兜底: 平均置信度<0.6 时指示模型输出 CLARIFY: 开头的澄清问题,
 * 流式侧用前缀缓冲检测, 转为 clarify 事件而非普通回答 (SSE契约 8.1)。
 */
@Component
public class Aggregator {
    public static final double CONFIDENCE_THRESHOLD = 0.6;
    public static final String CLARIFY_PREFIX = "CLARIFY:";
    private static final String SYS = """
            你是仲裁融合器。多位专家已就用户请求给出结构化意见(JSON), 你的任务:
            1. 融合为一份统一、连贯、可执行的中文行动方案, 分点组织, 保留专家意见中的[S#]引用标注。
            2. 处理冲突: 若专家结论互相矛盾, 选择证据更充分的一方并简要说明取舍理由。
            3. 各专家意见的关键内容都要覆盖, 不要只转述一位专家。
            4. 若整体置信度过低或专家结论根本性互斥无法融合, 则只输出一行:
               CLARIFY: <需要用户补充说明的具体问题>
            5. 结尾不要客套。
            """;

    private final LlmGateway gateway;

    public Aggregator(LlmGateway gateway) {
        this.gateway = gateway;
    }

    public interface AggregateEvents {
        void onToken(String token);
        void onClarify(String question);
        void onComplete(String fullText, boolean clarified);
        void onError(Throwable error);
    }

    public void aggregateStream(List<ExpertOutput> outputs, String question,
                                String profileText, String traceId, AggregateEvents events) {
        double avgConf = outputs.stream().mapToDouble(ExpertOutput::confidence).average().orElse(0);
        StringBuilder user = new StringBuilder();
        if (!profileText.isBlank()) user.append(profileText).append('\n');
        user.append("## 用户请求\n").append(question).append("\n\n## 专家意见\n");
        for (ExpertOutput o : outputs) {
            user.append("### ").append(o.expert()).append(" (自评置信度 ").append(o.confidence()).append(")\n")
                    .append(o.content()).append('\n');
        }
        user.append("\n专家平均置信度: ").append(String.format("%.2f", avgConf));
        if (avgConf < CONFIDENCE_THRESHOLD) {
            user.append(" (低于阈值").append(CONFIDENCE_THRESHOLD).append(", 若确实无法给出可靠方案请输出CLARIFY行)");
        }

        List<ChatMessage> messages = List.of(SystemMessage.from(SYS), UserMessage.from(user.toString()));
        ClarifyDetectingHandler handler = new ClarifyDetectingHandler(events);
        gateway.chatStream(Purpose.CHAT, messages, traceId, handler);
    }

    /** 前缀缓冲: 攒够前缀长度或流结束才判定是否CLARIFY, 之后正常透传 */
    static class ClarifyDetectingHandler implements StreamingChatResponseHandler {
        private final AggregateEvents events;
        private final StringBuilder buffer = new StringBuilder();
        private final StringBuilder full = new StringBuilder();
        private boolean decided = false;
        private boolean clarified = false;

        ClarifyDetectingHandler(AggregateEvents events) {
            this.events = events;
        }

        @Override
        public void onPartialResponse(String token) {
            full.append(token);
            if (decided) {
                if (!clarified) events.onToken(token);
                return;
            }
            buffer.append(token);
            String head = buffer.toString().stripLeading();
            if (head.length() < CLARIFY_PREFIX.length() && CLARIFY_PREFIX.startsWith(head)) {
                return; // 仍可能是CLARIFY前缀, 继续攒
            }
            decided = true;
            clarified = head.startsWith(CLARIFY_PREFIX);
            if (!clarified) events.onToken(buffer.toString()); // 非澄清: 补发已缓冲内容
        }

        @Override
        public void onCompleteResponse(ChatResponse response) {
            String text = full.toString().strip();
            if (!decided) { // 极短输出, 收尾时判定
                clarified = text.startsWith(CLARIFY_PREFIX);
                if (!clarified) events.onToken(text);
            }
            if (clarified) {
                events.onClarify(text.substring(text.indexOf(CLARIFY_PREFIX) + CLARIFY_PREFIX.length()).strip());
            }
            events.onComplete(text, clarified);
        }

        @Override
        public void onError(Throwable error) {
            events.onError(error);
        }
    }
}
