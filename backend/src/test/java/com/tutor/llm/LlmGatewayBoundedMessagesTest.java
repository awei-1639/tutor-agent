package com.tutor.llm;

import com.tutor.config.LlmProperties;
import com.tutor.contract.Purpose;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import com.tutor.context.TokenBudget;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class LlmGatewayBoundedMessagesTest {
    @Mock
    JdbcTemplate jdbc;
    @Mock
    LlmBudgetGuard budgetGuard;
    @Mock
    LlmConcurrencyGate concurrency;

    private final TokenBudget budget = new TokenBudget();

    /** 构造近似 N token 的中文文本（按 jtokkit 实测密度循环拼接）。 */
    private String textOfTokens(int tokens) {
        StringBuilder sb = new StringBuilder();
        while (budget.count(sb.toString()) < tokens) sb.append("学习计划需要结合岗位要求逐步推进。");
        return sb.toString();
    }

    private static String text(ChatMessage m) {
        if (m instanceof SystemMessage system) return system.text();
        if (m instanceof UserMessage user && user.hasSingleText()) return user.singleText();
        if (m instanceof AiMessage ai) return ai.text();
        throw new IllegalArgumentException("unsupported message type: " + m.type());
    }

    @Test
    void shortFinalQuestionLetsHistoryUseTheLeftoverBudget() {
        LlmGateway gateway = new LlmGateway(properties(), jdbc, budgetGuard, concurrency);

        // 总量超出 CHAT 输入上限 (8000)：系统 2600 + 历史 10×900 + 提问 30。
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(textOfTokens(2_600)));
        for (int i = 0; i < 10; i++) {
            messages.add(i % 2 == 0 ? UserMessage.from("u" + i + textOfTokens(880))
                    : AiMessage.from("a" + i + textOfTokens(880)));
        }
        String question = "继续";
        messages.add(UserMessage.from(question));

        List<ChatMessage> bounded = gateway.boundedMessages(Purpose.CHAT, messages);

        // 末条提问按实际大小保留 (不被截断、不带省略号)。
        assertThat(text(bounded.getLast())).isEqualTo(question);
        // 释放出的份额必须回流给历史：旧逻辑下中间只剩约 1000 token (≈1 条)，新逻辑远多于此。
        long retainedHistory = bounded.stream()
                .filter(m -> m != bounded.getFirst() && m != bounded.getLast())
                .count();
        assertThat(retainedHistory).isGreaterThanOrEqualTo(4);
        // 总量仍受输入上限约束。
        int total = bounded.stream().mapToInt(m -> budget.count(text(m))).sum();
        assertThat(total).isLessThanOrEqualTo(8_000);
    }

    @Test
    void oversizedFinalMessageIsStillCappedByShare() {
        LlmGateway gateway = new LlmGateway(properties(), jdbc, budgetGuard, concurrency);

        // 末条是 7000 token 的超长粘贴：必须被 55% 份额封顶，系统与历史各留余地。
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(textOfTokens(2_600)));
        messages.add(UserMessage.from("u0" + textOfTokens(800)));
        String huge = textOfTokens(7_000);
        messages.add(UserMessage.from(huge));

        List<ChatMessage> bounded = gateway.boundedMessages(Purpose.CHAT, messages);

        String last = text(bounded.getLast());
        assertThat(budget.count(last)).isLessThanOrEqualTo((int) Math.round(8_000 * 0.55));
        assertThat(last).endsWith("…");
        // 系统提示词与历史都保留了下来。
        assertThat(bounded.getFirst()).isInstanceOf(SystemMessage.class);
        assertThat(bounded.size()).isGreaterThanOrEqualTo(2);
        int total = bounded.stream().mapToInt(m -> budget.count(text(m))).sum();
        assertThat(total).isLessThanOrEqualTo(8_000);
    }

    private LlmProperties properties() {
        return new LlmProperties(
                new LlmProperties.Endpoint("deepseek-key", "https://api.deepseek.com"),
                new LlmProperties.Endpoint("silicon-key", "https://api.siliconflow.cn/v1"),
                Map.of("chat", "chat", "router", "router", "expert", "expert", "summary", "summary",
                        "extract", "extract", "embed", "embed"),
                new LlmProperties.Budget(100_000, 10_000),
                new LlmProperties.Timeout(1, 60, 120, 25), LlmProperties.TokenLimits.defaults());
    }
}
