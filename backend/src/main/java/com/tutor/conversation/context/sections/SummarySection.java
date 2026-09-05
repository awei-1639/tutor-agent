package com.tutor.conversation.context.sections;

import com.tutor.conversation.context.ContextSection;
import com.tutor.conversation.context.TokenBudget;
import com.tutor.conversation.context.TurnContextView;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 区5: 会话滚动摘要 (超12轮才有, 实现设计 2.1/3.1)。 */
@Component
@Order(5)
public class SummarySection implements ContextSection {

    @Override public String name() { return "summary"; }
    @Override public int budget() { return 800; }

    @Override
    public String render(TurnContextView ctx, TokenBudget budget) {
        String s = ctx.conversationSummary();
        if (s == null || s.isBlank()) return "";
        return budget.truncate("\n## 本会话早期内容摘要\n" + s + "\n", budget());
    }
}
